import { useEffect, useState } from 'react'

const STEPS = [
  {
    n: '01',
    t: 'Get the app',
    d: 'Tap Download above. Android will warn you because it did not come from the Play Store - that is normal for a direct download. Tap More details, then Install anyway.',
  },
  {
    n: '02',
    t: 'Open it and pick an AI',
    d: 'The app opens straight into a chat. Tap the menu, then Browse, and you will see every model sorted so the ones your phone handles best come first.',
  },
  {
    n: '03',
    t: 'Tap Install on one',
    d: 'It shows exactly what that model needs - download size against your free storage, memory needed against what your phone has - and lets you decide. Nothing downloads until you say yes.',
  },
  {
    n: '04',
    t: 'Wait once',
    d: 'The model downloads from Hugging Face, between 0.4 GB and 18 GB depending which one. It carries on in the background and resumes if your signal drops.',
  },
  {
    n: '05',
    t: 'Turn the internet off',
    d: 'Airplane mode, no SIM, a tunnel - it does not matter. The AI now runs on your phone’s own processor. Nothing you type ever leaves the device.',
  },
]

/** Collapsed by default: it answers a question, it should not shout it. */
function HowItWorks() {
  const [open, setOpen] = useState(false)
  return (
    <section className={'how' + (open ? ' open' : '')}>
      <button className="how-btn" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span>How it works</span>
        <i>{open ? '−' : '+'}</i>
      </button>
      {open && (
        <ol className="how-steps">
          {STEPS.map((s) => (
            <li key={s.n}>
              <b>{s.n}</b>
              <div>
                <strong>{s.t}</strong>
                <p>{s.d}</p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

const API = 'https://llmobi-api.gpmai.workers.dev/v1/catalog'
const APK = '/llmobi.apk'
const APK_SIZE = '7.2 MB'
const REPO = 'https://github.com/12ziyad/llmobi'

/**
 * Shown before the API responds, and kept if it never does. A visitor should
 * never see an empty store because a fetch was slow.
 */
const FALLBACK = [
  { id: 'qwen25-05b', name: 'Qwen 0.5B', sizeLabel: '0.40 GB', minRamMb: 1012, tier: 'tiny', speedHint: 'very fast', tagline: 'Tiny and instant.' },
  { id: 'llama32-1b', name: 'Llama 1B', sizeLabel: '0.95 GB', minRamMb: 1719, tier: 'tiny', speedHint: 'very fast', tagline: "Meta's small everyday assistant." },
  { id: 'gemma3-4b', name: 'Gemma 4B', sizeLabel: '2.3 GB', minRamMb: 3469, tier: 'fast', speedHint: 'fast', tagline: 'Everyday AI.' },
  { id: 'llama31-8b', name: 'Llama 8B', sizeLabel: '4.6 GB', minRamMb: 6366, tier: 'powerful', speedHint: 'steady', tagline: 'Strong general assistant.' },
]

const TIERS = [
  { key: 'tiny', label: 'Tiny', blurb: 'Runs on almost any phone' },
  { key: 'fast', label: 'Fast', blurb: 'For 6-8 GB phones' },
  { key: 'powerful', label: 'Powerful', blurb: 'For 12 GB phones' },
  { key: 'pro', label: 'Pro', blurb: 'For 16 GB flagships' },
  { key: 'extreme', label: 'Extreme', blurb: 'Experimental, 24 GB+' },
]

const BEATS = [
  { ms: 2600, net: true, label: '5G', cap: 'Tap install', sub: 'one time only', key: 'store' },
  { ms: 2500, net: true, label: '5G', cap: 'It downloads', sub: 'once — then never again', key: 'dl' },
  { ms: 2600, net: false, label: '✈ OFF', cap: 'Now cut the net', sub: 'airplane mode, no sim, tunnel', key: 'ready' },
  { ms: 4200, net: false, label: '✈ OFF', cap: 'It still works', sub: 'forever, on your phone alone', key: 'chat' },
]

const ANSWER = 'Frozen water — about 1.09 litres, since ice expands when it freezes.'

/** The four-beat story: install, download, go offline, still works. */
function Demo() {
  const [i, setI] = useState(0)
  const [pct, setPct] = useState(0)
  const [typed, setTyped] = useState('')
  const beat = BEATS[i]

  useEffect(() => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const t = setTimeout(() => setI((n) => (n + 1) % BEATS.length), reduce ? 3000 : beat.ms)
    return () => clearTimeout(t)
  }, [i, beat.ms])

  useEffect(() => {
    if (beat.key !== 'dl') { setPct(0); return }
    let p = 0
    const id = setInterval(() => {
      p += 4
      setPct(Math.min(p, 100))
      if (p >= 100) clearInterval(id)
    }, 80)
    return () => clearInterval(id)
  }, [beat.key])

  useEffect(() => {
    if (beat.key !== 'chat') { setTyped(''); return }
    let n = 0
    const id = setInterval(() => {
      n += 1
      setTyped(ANSWER.slice(0, n))
      if (n >= ANSWER.length) clearInterval(id)
    }, 42)
    return () => clearInterval(id)
  }, [beat.key])

  return (
    <div className="demo">
      <div className="demo-hd">
        <span>Watch it work</span>
        <b>0{i + 1} / 04</b>
      </div>

      <div className="ph">
        <div className="ph-scr">
          <div className={'ph-st' + (beat.net ? '' : ' off')}>
            <span>9:41</span>
            <span className="st-r">
              <span className="netlbl">{beat.label}</span>
              <span className="sig"><i /><i /><i /><i /></span>
            </span>
          </div>

          <div className="ph-bd">
            {beat.key === 'store' && (
              <>
                <div className="ph-ttl">LLMobi</div>
                <div className="pcard">
                  <div className="pcard-top">
                    <div className="pico">G</div>
                    <div>
                      <div className="pnm">Gemma 1B</div>
                      <div className="psz">0.94 GB · everyday AI</div>
                    </div>
                  </div>
                  <div className="pbtn">Install</div>
                  <div className="ripple" />
                </div>
                <div className="pfoot">RUNS GREAT ON THIS PHONE</div>
              </>
            )}

            {beat.key === 'dl' && (
              <>
                <div className="ph-ttl">Installing</div>
                <div className="pcard">
                  <div className="pcard-top">
                    <div className="pico">G</div>
                    <div>
                      <div className="pnm">Gemma 1B</div>
                      <div className="psz">downloading…</div>
                    </div>
                  </div>
                  <div className="ptrack"><div className="pfill" style={{ width: pct + '%' }} /></div>
                  <div className="pnums"><span>0.94 GB</span><span>WI-FI</span></div>
                </div>
              </>
            )}

            {beat.key === 'ready' && (
              <div className="pready">
                <div className="ptick">✓</div>
                <div className="pdone">Gemma is ready</div>
                <div className="pofflbl">INTERNET OFF</div>
              </div>
            )}

            {beat.key === 'chat' && (
              <>
                <div className="pb me">what is 1kg of ice?</div>
                <div className="pb ai">{typed}<span className="pcar" /></div>
                <div className="pin">Ask anything…</div>
              </>
            )}
          </div>
        </div>
      </div>

      <div className="cap"><b>{beat.cap}</b><i>{beat.sub}</i></div>
      <div className="dots">
        {BEATS.map((_, n) => <i key={n} className={n === i ? 'on' : ''} />)}
      </div>
    </div>
  )
}

/**
 * The live catalog, straight from the same API the phone reads. The site and the
 * app can never disagree about what is available, because there is only one list.
 */
function Models() {
  const [models, setModels] = useState(FALLBACK)
  const [state, setState] = useState('loading')

  useEffect(() => {
    let alive = true
    fetch(API)
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((d) => {
        if (!alive || !d.models?.length) return
        setModels(d.models)
        setState('live')
      })
      .catch(() => alive && setState('offline'))
    return () => { alive = false }
  }, [])

  const maxRam = Math.max(...models.map((m) => m.minRamMb || 0), 1)

  return (
    <section className="models" id="models">
      <div className="mh">
        <h2>Model library</h2>
        <span>
          {state === 'live' ? `${models.length} models · live` : state === 'loading' ? 'loading…' : 'offline'}
        </span>
      </div>

      {TIERS.map((tier) => {
        const rows = models.filter((m) => (m.tier || '').toLowerCase() === tier.key)
        if (!rows.length) return null
        return (
          <div className="tier" key={tier.key}>
            <div className="tier-hd">
              <b>{tier.label}</b>
              <span>{tier.blurb}</span>
            </div>
            {rows.map((m) => {
              const gb = (m.minRamMb / 1024)
              const heavy = m.minRamMb > 12000
              return (
                <div className="row" key={m.id}>
                  <div>
                    <div className="rn">{m.name}</div>
                    <div className="rs">{m.sizeLabel} · {m.speedHint}</div>
                  </div>
                  <div>
                    <div className="bar">
                      <i className={heavy ? 'hot' : ''} style={{ width: (m.minRamMb / maxRam) * 100 + '%' }} />
                    </div>
                    <div className="need">{gb < 10 ? gb.toFixed(1) : Math.round(gb)} GB</div>
                  </div>
                </div>
              )
            })}
          </div>
        )
      })}

      <p className="mnote">
        The app reads your phone and <b>hides what won't run.</b><br />
        You never have to work any of this out yourself.
      </p>
    </section>
  )
}

export default function App() {
  return (
    <div className="site">
      <header className="top">
        <div className="brand">
          <img src="/icon.svg" alt="" width="26" height="26" />
          LL<em>MOBI</em>
        </div>
        <div className="top-r">
          <a className="src" href={REPO} target="_blank" rel="noopener noreferrer">
            <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" fill="currentColor">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38
                0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01
                1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95
                0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.42 7.42 0 0 1 2-.27c.68
                0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15
                0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01
                8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
            </svg>
            Open source
          </a>
          <div className="pill"><span className="pip" />Offline</div>
        </div>
      </header>

      <div className="split">
        <div className="hero">
          <div className="eyebrow">Local AI — Android</div>
          <h1>AI that never<br />leaves<span>your phone</span></h1>
          <p className="sub">
            Not a chatbot that phones a datacentre. The whole model downloads once
            and then runs on <b>your phone's own chip</b> — on a plane, in a tunnel,
            with the SIM taken out. Nothing you type ever leaves the device.
          </p>
          <Demo />
          <div className="cta">
            <a className="dl" href={APK} download>Download for Android</a>
            <div className="fine">
              <span>{APK_SIZE}</span><span>·</span><span>ANDROID 8+</span><span>·</span><span>NO ACCOUNT</span>
            </div>
          </div>
          <HowItWorks />
        </div>

        <div className="side">
          <Models />
        </div>
      </div>

      <footer className="foot">
        <div className="fgrid">
          <div className="f"><b>No cloud</b><span>Nothing is sent anywhere</span></div>
          <div className="f"><b>No account</b><span>Open it and use it</span></div>
          <div className="f"><b>No bill</b><span>Free after download</span></div>
        </div>
        <div className="licence">
          <b>Open source, Apache 2.0.</b> Every line of this app is public — you can
          read exactly what it does with your data, which is the only way a privacy
          claim is worth anything.{' '}
          <a href={REPO} target="_blank" rel="noopener noreferrer">Read the code →</a>
        </div>
        <div className="fend">
          <span>LLMOBI © 2026 · APACHE 2.0</span>
          <b>LOCAL AI, ONE TAP AWAY</b>
        </div>
      </footer>
    </div>
  )
}
