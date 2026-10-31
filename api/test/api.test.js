import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Miniflare, convertV4MiniflareOptions } from 'miniflare'
import worker from '../src/index.js'

let runtime, env
function context() {
  const pending = []
  return { waitUntil(p) { pending.push(p) }, async finish() { await Promise.all(pending) } }
}
async function request(path, options = {}) {
  const ctx = context()
  const res = await worker.fetch(new Request(`https://llmobi.test${path}`, options), env, ctx)
  await ctx.finish()
  return res
}
before(async () => {
  runtime = new Miniflare(convertV4MiniflareOptions({ modules: true, script: 'export default { fetch() { return new Response("ok") } }', d1Databases: ['DB'], kvNamespaces: ['CACHE'] }))
  env = { DB: await runtime.getD1Database('DB'), CACHE: await runtime.getKVNamespace('CACHE') }
  for (const name of ['schema.sql', 'seed.sql']) {
    const sql = (await readFile(new URL(`../${name}`, import.meta.url), 'utf8')).replace(/^--.*$/gm, '')
    for (const statement of sql.split(';').map(s => s.trim()).filter(Boolean)) await env.DB.prepare(statement).run()
  }
})
after(async () => { await runtime?.dispose() })

test('live catalog returns 20 models and conditional requests return 304', async () => {
  const res = await request('/v1/catalog')
  assert.equal(res.status, 200)
  assert.equal((await res.json()).models.length, 20)
  assert.equal(res.headers.get('access-control-allow-origin'), '*')
  assert.equal((await request('/v1/catalog', { headers: { 'If-None-Match': res.headers.get('etag') } })).status, 304)
})
test('dead links become broken; temporary server errors stay live; cache is invalidated', async t => {
  t.mock.method(globalThis, 'fetch', async url => new Response(null, {
    status: String(url).includes('Qwen2.5-0.5B') ? 404 : 503,
  }))
  const ctx = context()
  await worker.scheduled({}, env, ctx); await ctx.finish()
  assert.equal((await env.DB.prepare('SELECT status FROM models WHERE id=?').bind('qwen25-05b').first()).status, 'broken')
  assert.equal((await env.DB.prepare('SELECT status FROM models WHERE id=?').bind('llama32-1b').first()).status, 'live')
  assert.equal(await env.CACHE.get('catalog:v1'), null)
  assert.equal((await request('/v1/model/qwen25-05b')).status, 404)
})
test('an upstream recovery restores the model to the catalog', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 200 }))
  const ctx = context(); await worker.scheduled({}, env, ctx); await ctx.finish()
  assert.equal((await request('/v1/model/qwen25-05b')).status, 200)
})
test('hidden overrides apply to individual model endpoints', async () => {
  await env.DB.prepare('INSERT INTO overrides(id,hidden) VALUES(?,1)').bind('qwen25-05b').run()
  assert.equal((await request('/v1/model/qwen25-05b')).status, 404)
  await env.DB.prepare('DELETE FROM overrides WHERE id=?').bind('qwen25-05b').run()
})
test('HEAD probes never increase download counts; GET still redirects and counts', async () => {
  const probe = await request('/dl', { method: 'HEAD' })
  assert.equal(probe.status, 302)
  assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM downloads').first()).n, 0)
  const res = await request('/dl')
  assert.equal(res.headers.get('location'), 'https://llmobi.pages.dev/llmobi.apk')
  assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM downloads').first()).n, 1)
})
test('the update endpoint has a defined response even before the first release is published', async () => {
  const res = await request('/v1/release')
  assert.equal(res.status, 200)
  assert.ok(Object.hasOwn(await res.json(), 'release'))
})
test('schema initialization preserves existing download and model records', async () => {
  const sql = (await readFile(new URL('../schema.sql', import.meta.url), 'utf8')).replace(/^--.*$/gm, '')
  for (const statement of sql.split(';').map(s => s.trim()).filter(Boolean)) await env.DB.prepare(statement).run()
  assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM downloads').first()).n, 1)
  assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM models').first()).n, 20)
})

test('catalog probes refresh upstream hashes, sizes and memory requirements together', async t => {
  const hash = 'b'.repeat(64)
  t.mock.method(globalThis, 'fetch', async url => String(url).includes('Qwen2.5-0.5B')
    ? new Response(null, { status: 302, headers: { 'x-linked-size': '500000000', 'x-linked-etag': `"${hash}"` } })
    : new Response(null, { status: 429 }))
  const ctx = context(); await worker.scheduled({}, env, ctx); await ctx.finish()
  const row = await env.DB.prepare('SELECT * FROM models WHERE id=?').bind('qwen25-05b').first()
  assert.equal(row.sha256, hash)
  assert.equal(row.file_bytes, 500000000)
  assert.equal(row.min_ram_mb, Math.ceil(500000000 / 1048576 * 1.25 + 500))
})

test('re-seeding preserves hidden status, editorial overrides and download history', async () => {
  await env.DB.prepare("UPDATE models SET status='hidden' WHERE id=?").bind('qwen25-05b').run()
  await env.DB.prepare('INSERT INTO overrides(id,name) VALUES(?,?)').bind('qwen25-05b', 'Custom name').run()
  const sql = (await readFile(new URL('../seed.sql', import.meta.url), 'utf8')).replace(/^--.*$/gm, '')
  for (const statement of sql.split(';').map(s => s.trim()).filter(Boolean)) await env.DB.prepare(statement).run()
  assert.equal((await env.DB.prepare('SELECT status FROM models WHERE id=?').bind('qwen25-05b').first()).status, 'hidden')
  assert.equal((await env.DB.prepare('SELECT name FROM overrides WHERE id=?').bind('qwen25-05b').first()).name, 'Custom name')
  assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM downloads').first()).n, 1)
})

test('database failure returns a recoverable response without exposing internal details', async t => {
  t.mock.method(console, 'error', () => {})
  const res = await worker.fetch(new Request('https://llmobi.test/v1/health'), {
    DB: { prepare() { throw new Error('private database detail') } },
  }, context())
  assert.equal(res.status, 503)
  assert.deepEqual(await res.json(), { error: 'temporarily unavailable' })
})
