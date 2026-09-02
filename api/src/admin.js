/**
 * Download counting and the stats dashboard.
 *
 * The counting is deliberately anonymous. There is no cookie, no identifier and
 * no IP stored - only a timestamp, a two-letter country and an Android major
 * version, all of which Cloudflare hands us in request headers anyway. That is
 * enough to answer "is anybody installing this" and not enough to follow anyone
 * around, which is the correct trade for a product whose whole promise is that
 * nothing leaves your phone.
 */

const APK_URL = 'https://llmobi.pages.dev/llmobi.apk'

/** Android major version out of a user agent, or null. */
function androidVersion(ua) {
  const m = /Android\s+(\d+)/i.exec(ua || '')
  return m ? m[1] : null
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

/**
 * GET /dl - count, then send them to the file.
 *
 * The redirect happens regardless of whether the write succeeds. A download
 * failing because a stats insert timed out would be an absurd trade.
 */
export async function download(request, env, ctx) {
  const ua = request.headers.get('user-agent') || ''
  const country = request.headers.get('cf-ipcountry') || null
  const android = androidVersion(ua)

  ctx.waitUntil(
    env.DB.prepare(
      'INSERT INTO downloads (at, day, country, android, platform) VALUES (?, ?, ?, ?, ?)'
    )
      .bind(Date.now(), today(), country, android, android ? 'android' : 'other')
      .run()
      .catch(() => {})
  )

  return Response.redirect(APK_URL, 302)
}

// The dashboard is deliberately open: it is read-only and shows nothing but
// aggregate counts - no IP addresses, no identifiers, nothing per-person is
// stored in the first place. A password only made it awkward to check from a
// phone. Search engines are told to stay away so it is not stumbled upon.

const PRIVATE = { 'x-robots-tag': 'noindex, nofollow' }

// ---------------------------------------------------------------- stats

async function gather(env) {
  const q = (sql, ...binds) => env.DB.prepare(sql).bind(...binds).all()

  const [total, byDay, byCountry, byAndroid, models, broken] = await Promise.all([
    q('SELECT COUNT(*) AS n FROM downloads'),
    q(`SELECT day, COUNT(*) AS n FROM downloads
        WHERE day >= date('now','-29 days') GROUP BY day ORDER BY day`),
    q(`SELECT COALESCE(country,'??') AS c, COUNT(*) AS n FROM downloads
        GROUP BY c ORDER BY n DESC LIMIT 12`),
    q(`SELECT COALESCE(android,'unknown') AS v, COUNT(*) AS n FROM downloads
        GROUP BY v ORDER BY n DESC LIMIT 8`),
    q("SELECT COUNT(*) AS n FROM models WHERE status='live'"),
    q("SELECT id, url FROM models WHERE status='broken'"),
  ])

  const days = byDay.results || []
  const sum = (from) =>
    days.filter((d) => d.day >= from).reduce((a, d) => a + d.n, 0)

  const d = (n) => {
    const x = new Date()
    x.setUTCDate(x.getUTCDate() - n)
    return x.toISOString().slice(0, 10)
  }

  return {
    total: total.results?.[0]?.n ?? 0,
    today: sum(d(0)),
    week: sum(d(6)),
    month: sum(d(29)),
    days,
    countries: byCountry.results || [],
    androids: byAndroid.results || [],
    liveModels: models.results?.[0]?.n ?? 0,
    broken: broken.results || [],
  }
}

export async function stats(request, env) {
  return new Response(JSON.stringify(await gather(env), null, 2), {
    headers: { 'content-type': 'application/json; charset=utf-8', ...PRIVATE },
  })
}

// ---------------------------------------------------------------- page

const esc = (s) =>
  String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

export async function admin(request, env) {
  const s = await gather(env)

  const peak = Math.max(1, ...s.days.map((d) => d.n))
  // Always draw 30 columns, so an empty day is visibly empty rather than absent.
  const grid = []
  for (let i = 29; i >= 0; i--) {
    const dt = new Date()
    dt.setUTCDate(dt.getUTCDate() - i)
    const key = dt.toISOString().slice(0, 10)
    const hit = s.days.find((d) => d.day === key)
    grid.push({ day: key, n: hit ? hit.n : 0 })
  }

  const bars = grid
    .map(
      (d) =>
        `<div class="bar" title="${d.day}: ${d.n}">` +
        `<i style="height:${Math.max(2, (d.n / peak) * 100)}%"></i>` +
        `</div>`
    )
    .join('')

  const countries = s.countries.length
    ? s.countries
        .map(
          (c) =>
            `<tr><td>${esc(c.c)}</td><td class="n">${c.n}</td>` +
            `<td class="pc"><i style="width:${(c.n / s.total) * 100}%"></i></td></tr>`
        )
        .join('')
    : '<tr><td colspan="3" class="empty">No downloads yet</td></tr>'

  const androids = s.androids.length
    ? s.androids
        .map((a) => `<tr><td>Android ${esc(a.v)}</td><td class="n">${a.n}</td></tr>`)
        .join('')
    : '<tr><td colspan="2" class="empty">No downloads yet</td></tr>'

  const brokenRows = s.broken.length
    ? s.broken.map((b) => `<li>${esc(b.id)}</li>`).join('')
    : '<li class="ok">All model links resolving</li>'

  return new Response(
    `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LLMobi admin</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600;700&family=Big+Shoulders+Display:wght@800;900&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
:root{--ink:#16181A;--ink2:#1D2124;--ink3:#242A2D;--line:#2C3033;--bone:#E8E6E1;
      --grey:#A8A49C;--grey2:#6E6A63;--red:#E5342A;--green:#4FCB94;color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:var(--ink);color:var(--bone);
     font-family:Archivo,system-ui,sans-serif;-webkit-font-smoothing:antialiased}
.wrap{max-width:980px;margin:0 auto;padding:34px 20px 70px}
h1{margin:0;font-family:"Big Shoulders Display",sans-serif;font-weight:900;
   font-size:38px;text-transform:uppercase;letter-spacing:.02em}
h1 em{font-style:normal;color:var(--red)}
h2{margin:34px 0 12px;font-family:"Big Shoulders Display",sans-serif;font-weight:800;
   font-size:20px;text-transform:uppercase;letter-spacing:.05em}
.mono{font-family:"JetBrains Mono",monospace}
.sub{font-family:"JetBrains Mono",monospace;font-size:10px;letter-spacing:.16em;
     text-transform:uppercase;color:var(--grey2);margin-top:8px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-top:24px}
.card{background:var(--ink2);border:1px solid var(--line);padding:18px}
.card b{display:block;font-family:"Big Shoulders Display",sans-serif;font-weight:900;
        font-size:40px;line-height:1;color:var(--red)}
.card span{display:block;font-family:"JetBrains Mono",monospace;font-size:9px;
           letter-spacing:.12em;text-transform:uppercase;color:var(--grey2);margin-top:8px}
.chart{background:var(--ink2);border:1px solid var(--line);padding:18px}
.bars{display:flex;align-items:flex-end;gap:3px;height:120px}
.bar{flex:1;height:100%;display:flex;align-items:flex-end}
.bar i{display:block;width:100%;background:var(--red);min-height:2px}
.axis{display:flex;justify-content:space-between;margin-top:9px;
      font-family:"JetBrains Mono",monospace;font-size:8px;letter-spacing:.1em;color:var(--grey2)}
table{width:100%;border-collapse:collapse;background:var(--ink2);border:1px solid var(--line)}
td{padding:9px 14px;border-bottom:1px solid #22262A;font-size:13.5px;color:var(--grey)}
tr:last-child td{border-bottom:none}
td.n{text-align:right;font-family:"JetBrains Mono",monospace;font-size:12px;color:var(--bone);width:70px}
td.pc{width:45%}
td.pc i{display:block;height:6px;background:var(--red)}
td.empty{color:var(--grey2);text-align:center;font-family:"JetBrains Mono",monospace;font-size:11px}
ul{margin:0;padding:0;list-style:none;background:var(--ink2);border:1px solid var(--line)}
li{padding:9px 14px;border-bottom:1px solid #22262A;font-size:13.5px;color:var(--red)}
li:last-child{border-bottom:none}
li.ok{color:var(--green)}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:14px}
@media(max-width:700px){.cols{grid-template-columns:1fr}}
footer{margin-top:34px;padding-top:16px;border-top:1px solid var(--line);
       font-family:"JetBrains Mono",monospace;font-size:9px;letter-spacing:.1em;color:var(--grey2);line-height:2}
</style></head><body><div class="wrap">

<h1>LL<em>MOBI</em> ADMIN</h1>
<div class="sub">${s.liveModels} models live &middot; refreshed ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC</div>

<div class="cards">
  <div class="card"><b>${s.total}</b><span>Downloads<br>all time</span></div>
  <div class="card"><b>${s.today}</b><span>Today</span></div>
  <div class="card"><b>${s.week}</b><span>Last 7 days</span></div>
  <div class="card"><b>${s.month}</b><span>Last 30 days</span></div>
</div>

<h2>Downloads, last 30 days</h2>
<div class="chart">
  <div class="bars">${bars}</div>
  <div class="axis"><span>${grid[0].day}</span><span>PEAK ${peak}/DAY</span><span>${grid[grid.length - 1].day}</span></div>
</div>

<div class="cols">
  <div>
    <h2>Countries</h2>
    <table>${countries}</table>
  </div>
  <div>
    <h2>Android version</h2>
    <table>${androids}</table>
    <h2>Catalog health</h2>
    <ul>${brokenRows}</ul>
  </div>
</div>

<footer>
  NO COOKIES &middot; NO IP ADDRESSES &middot; NO IDENTIFIERS STORED<br>
  A ROW HERE IS A TIMESTAMP, A COUNTRY AND AN ANDROID VERSION. NOTHING ELSE.<br>
  RAW JSON AT /admin/stats
</footer>

</div></body></html>`,
    { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', ...PRIVATE } }
  )
}
