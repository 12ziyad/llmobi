/**
 * LLMobi catalog API.
 *
 * The phone makes exactly two kinds of network call in its whole life:
 *   1. GET /v1/catalog  - about 40 KB of JSON, once a day, ETag'd
 *   2. GET <the model url> - straight to R2 or Hugging Face, never through here
 *
 * Keeping the weights out of this Worker is the entire reason the service costs
 * a few dollars a month instead of tens of thousands.
 */

import { RELEASE } from './release.js'
import { download, admin, stats } from './admin.js'

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'access-control-expose-headers': 'etag',
  'x-content-type-options': 'nosniff',
}

const CACHE_KEY = 'catalog:v1'
const CACHE_TTL = 3600

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url)
    const path = url.pathname.replace(/\/+$/, '') || '/'

    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'GET, HEAD, OPTIONS',
          'access-control-allow-headers': 'If-None-Match',
          'access-control-max-age': '86400',
        },
      })
    }
    // HEAD is allowed as well as GET: download managers and link previewers
    // probe with HEAD first, and answering 405 makes them give up.
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return json({ error: 'method not allowed' }, 405)
    }

    // Counting happens here rather than on the static file, because Pages
    // serves that directly and never tells us it happened.
    if (path === '/dl' || path === '/download') return await download(request, env, ctx)
    if (path === '/admin') return await admin(request, env)
    if (path === '/admin/stats') return await stats(request, env)

    try {
      if (path === '/' || path === '/v1') return json({ ok: true, service: 'llmobi-api', version: 1 })
      if (path === '/v1/release') return json({ release: RELEASE })
      if (path === '/v1/catalog') return await catalog(request, env, ctx)
      if (path.startsWith('/v1/model/')) return await model(path.slice('/v1/model/'.length), env)
      if (path === '/v1/search') return await search(url, env)
      if (path === '/v1/health') return await health(env)
      return json({ error: 'not found' }, 404)
    } catch (err) {
      console.error('request failed', { path, message: String(err?.message) })
      return json({ error: 'temporarily unavailable' }, 503)
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(ingest(env))
  },
}

// ---------------------------------------------------------------- routes

async function catalog(request, env, ctx) {
  let body = await env.CACHE.get(CACHE_KEY)

  if (!body) {
    const { results } = await env.DB.prepare(
      `SELECT m.*, o.name AS o_name, o.tagline AS o_tagline, o.tier AS o_tier,
              o.category AS o_category, o.hidden AS o_hidden
         FROM models m
    LEFT JOIN overrides o ON o.id = m.id
        WHERE m.status = 'live'
     ORDER BY m.min_ram_mb ASC`
    ).all()

    const models = (results || [])
      .filter((r) => !r.o_hidden)
      .map(shape)

    body = JSON.stringify({ version: 1, count: models.length, models })
    ctx.waitUntil(env.CACHE.put(CACHE_KEY, body, { expirationTtl: CACHE_TTL }))
  }

  // Cheap, stable ETag. A repeat caller almost always gets a 304 and we serve
  // nothing at all - which is why 100k daily users still costs about nothing.
  const etag = `W/"${await hash(body)}"`
  if (request.headers.get('if-none-match') === etag) {
    return new Response(null, { status: 304, headers: { etag, ...JSON_HEADERS } })
  }

  return new Response(body, {
    headers: { ...JSON_HEADERS, etag, 'cache-control': 'public, max-age=3600' },
  })
}

async function model(id, env) {
  if (!/^[a-z0-9-]{1,64}$/.test(id)) return json({ error: 'bad id' }, 400)
  const row = await env.DB.prepare(`SELECT m.*, o.name AS o_name, o.tagline AS o_tagline, o.tier AS o_tier, o.category AS o_category FROM models m LEFT JOIN overrides o ON o.id = m.id WHERE m.id = ? AND m.status = 'live' AND COALESCE(o.hidden,0) = 0`).bind(id).first()
  if (!row) return json({ error: 'not found' }, 404)
  return json(shape(row))
}

/**
 * Advanced-mode search. Proxied so the app never needs a Hugging Face token and
 * so we can filter to things that stand a chance of running on a phone.
 */
async function search(url, env) {
  const q = (url.searchParams.get('q') || '').slice(0, 80)
  if (!q) return json({ models: [] })

  const hf = new URL('https://huggingface.co/api/models')
  hf.searchParams.set('search', q)
  hf.searchParams.set('filter', 'gguf')
  hf.searchParams.set('sort', 'downloads')
  hf.searchParams.set('limit', '25')

  const res = await fetch(hf, { headers: { 'user-agent': 'llmobi/1.0' } })
  if (!res.ok) return json({ models: [], error: 'upstream' }, 502)

  const list = await res.json()
  return json({
    models: list.map((m) => ({
      repo: m.modelId || m.id,
      downloads: m.downloads ?? 0,
      likes: m.likes ?? 0,
      updated: m.lastModified ?? null,
      community: true,
    })),
  })
}

async function health(env) {
  const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM models WHERE status='live'").first()
  return json({ ok: true, live_models: row?.n ?? 0 })
}

// ---------------------------------------------------------------- shaping

/** Database row -> exactly the JSON the Android app expects. */
function shape(r) {
  return {
    id: r.id,
    name: r.o_name || r.name,
    tagline: r.o_tagline || r.tagline,
    tier: r.o_tier || r.tier,
    category: r.o_category || r.category,
    iconLetter: r.icon_letter,
    colorStart: r.color_start,
    colorEnd: r.color_end,
    sizeLabel: r.size_label,
    speedHint: r.speed_hint,
    fileBytes: r.file_bytes,
    minRamMb: r.min_ram_mb,
    ctxDefault: r.ctx_default,
    arch: r.arch,
    quant: r.quant,
    // Prefer our own mirror: faster nearly everywhere, and it cannot 404 when
    // somebody renames a repo upstream.
    url: r.mirror_url || r.url,
    fallbackUrl: r.mirror_url ? r.url : null,
    sha256: r.sha256 || '',
    license: r.license,
  }
}

// ---------------------------------------------------------------- ingest

/**
 * Weekly maintenance.
 *
 * Only checks that every listed file still exists and that our recorded size is
 * right. Adding a model is a deliberate human act - an automatic crawler would
 * fill the store with junk, and curation is the product.
 */
async function ingest(env) {
  const { results } = await env.DB.prepare(
    "SELECT id, url, file_bytes, sha256 FROM models WHERE status != 'hidden'"
  ).all()

  for (const m of results || []) {
    try {
      const probe = await fetch(m.url, { method: 'HEAD', redirect: 'manual', signal: AbortSignal.timeout(15000) })
      const linkedBytes = Number(probe.headers.get('x-linked-size') || 0)
      const linkedHash = (probe.headers.get('x-linked-etag') || '').replaceAll('"', '')
      const knownFile = [301, 302, 307, 308].includes(probe.status) && linkedBytes > 0 && /^[a-f0-9]{64}$/i.test(linkedHash)
      const head = !knownFile && probe.status >= 300 && probe.status < 400
        ? await fetch(m.url, { method: 'HEAD', redirect: 'follow', signal: AbortSignal.timeout(15000) }) : probe
      if (head.status === 404 || head.status === 410) {
        await env.DB.prepare("UPDATE models SET status='broken', updated_at=? WHERE id=?")
          .bind(Date.now(), m.id).run()
        continue
      }
      if (!head.ok && !knownFile) continue
      const len = knownFile ? linkedBytes : Number(head.headers.get('content-length') || 0)
      const bytes = len > 0 ? len : m.file_bytes
      const sha = /^[a-f0-9]{64}$/i.test(linkedHash) ? linkedHash.toLowerCase() : (bytes === m.file_bytes ? m.sha256 : '')
      await env.DB.prepare(
        "UPDATE models SET file_bytes=?, size_label=?, min_ram_mb=?, sha256=?, status='live', updated_at=? WHERE id=?"
      ).bind(bytes, gbLabel(bytes), Math.ceil(bytes / 1048576 * 1.25 + 500), sha || '', Date.now(), m.id).run()

    } catch (err) {
      console.warn('catalog check failed', { id: m.id, message: String(err?.message) })
      // Leave the row alone: a transient upstream blip must not delist a model.
    }
  }

  await env.CACHE.delete(CACHE_KEY)
}

// ---------------------------------------------------------------- helpers

function gbLabel(bytes) {
  const gb = bytes / 1073741824
  return gb < 1 ? `${Math.round(bytes / 104857600) / 10} GB` : `${Math.round(gb * 10) / 10} GB`
}

async function hash(text) {
  const buf = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(text))
  return [...new Uint8Array(buf)].slice(0, 8).map((b) => b.toString(16).padStart(2, '0')).join('')
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: JSON_HEADERS })
}
