import { useEffect, useState } from 'react'

const STEPS = [
  {
    n: '01',
    t: 'Get the app',
    d: 'Tap Download above, open the APK, and follow Android’s installation prompts. Your phone may ask you to allow installs from your browser.',
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
    d: 'The model downloads from Hugging Face, between 0.4 GB and 18 GB depending which one. Interrupted model downloads can resume.',
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
// Counted route rather than the file itself - Pages serves static files
// directly and never tells us a download happened.
const APK = 'https://llmobi-api.gpmai.workers.dev/dl'
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
  { key: 'tiny', label: 'Tiny', blurb: 'The smallest and fastest' },
  { key: 'fast', label: 'Fast', blurb: 'For 6-8 GB phones' },
  { key: 'powerful', label: 'Powerful', blurb: 'For 12 GB phones' },
  { key: 'pro', label: 'Pro', blurb: 'For 16 GB flagships' },
  { key: 'extreme', label: 'Extreme', blurb: 'Experimental, 24 GB+' },
]

/**
 * The live catalog, straight from the same API the phone reads. The site and the
 * app can never disagree about what is available, because there is only one list.
 */
function Models() {
  const [models, setModels] = useState(FALLBACK)
  const [state, setState] = useState('loading')

  useEffect(() => {
    let alive = true
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 10000)
    fetch(API, { signal: controller.signal })
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((d) => {
        if (!alive) return
        if (!Array.isArray(d.models) || !d.models.length) throw new Error('Empty catalog')
        setModels(d.models)
        setState('live')
      })
      .catch(() => alive && setState('offline'))
      .finally(() => clearTimeout(timeout))
    return () => { alive = false; clearTimeout(timeout); controller.abort() }
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
        The app checks your phone and <b>tells you what each one needs</b><br />
        before you install. Then it is your call.
      </p>
    </section>
  )
}

export default function App() {
  const [release, setRelease] = useState(null)
  useEffect(() => {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 10000)
    fetch('https://llmobi-api.gpmai.workers.dev/v1/release', { signal: controller.signal })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        const r = data?.release
        if (r && /^\d+\.\d+\.\d+$/.test(r.versionName) && r.bytes > 0) setRelease(r)
      }).catch(() => {}).finally(() => clearTimeout(timer))
    return () => { clearTimeout(timer); controller.abort() }
  }, [])
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
          {/* The break after "on" only exists on narrow screens: four lines there,
              three on desktop. Left to wrap on its own it broke differently at
              every width and the shape fell apart. */}
          <h1>
            Use AI on<br className="nb" />{' '}your phone<br />
            with no internet<span>and no bill</span>
          </h1>
          <p className="sub">
            Download the app and a model, then chat offline.
            {' '}<b>No account or subscription.</b>
          </p>
          <div className="cta">
            <a className="dl" href={APK}>Download for Android</a>
            <div className="fine">
              <span>{release ? `${(release.bytes / 1048576).toFixed(1)} MB · v${release.versionName}` : APK_SIZE}</span><span>·</span><span>ANDROID 8+</span><span>·</span><span>NO ACCOUNT</span>
            </div>
          </div>
          <p className="compatibility">
            Requires a compatible 64-bit ARM phone. Some older processors are unsupported;
            the app checks before you download a model.
          </p>
          <p className="download-help">Download not starting? <a href="/llmobi.apk" download>Use the direct APK link</a>.</p>
          <p className="download-help">Your chats stay on your phone. <a href="#privacy">See how privacy works</a>.</p>
          <HowItWorks />
          <p className="compatibility">Model recommendations use your available memory and storage.
            Catalog fixes arrive when you are online. New app versions appear in Settings for you to install;
            your chats and downloaded models stay on your phone.</p>
        </div>

        <div className="side">
          <Models />
        </div>
      </div>

      <footer className="foot">
        <div className="fgrid">
          <div className="f"><b>Private chats</b><span>Processed on your phone</span></div>
          <div className="f"><b>No account</b><span>Open it and use it</span></div>
          <div className="f"><b>No bill</b><span>Free after download</span></div>
        </div>
        <details className="privacy" id="privacy">
          <summary>Privacy: what stays on your phone</summary>
          <div>
            <p><strong>Your conversations stay local.</strong> The app processes messages on your phone
              and does not upload your chats to LLMobi or an AI service. No account is required.</p>
            <p><strong>Online requests have a specific purpose.</strong> The app connects to download models
              and app updates, and to check the model catalog and available releases. These requests do not include your chats.</p>
            <p><strong>The website counts APK downloads.</strong> Our download counter stores the time,
              country, Android major version when available, and platform category. It does not store
              IP addresses, account details or a persistent user identifier, and does not use tracking cookies.</p>
            <p><strong>Service providers receive normal connection information.</strong> Hosting, font and
              model-download providers can receive your IP address and browser or app request information
              when you connect to them. This is separate from your on-device conversations.</p>
          </div>
        </details>
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
