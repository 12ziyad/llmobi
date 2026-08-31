import { useEffect, useState } from 'react'

// The launch catalog. Later this comes from the Worker at /v1/catalog so the
// site and the app always agree; the shape is identical either way.
const MODELS = [
  { name: 'Gemma 3 · 1B',   size: '0.8 GB',  ram: 2,  bar: 0.16, tag: 'very fast' },
  { name: 'Llama 3.2 · 3B', size: '2.0 GB',  ram: 4,  bar: 0.32, tag: 'fast' },
  { name: 'Qwen 3 · 4B',    size: '2.5 GB',  ram: 5,  bar: 0.41, tag: 'fast' },
  { name: 'Llama 3.1 · 8B', size: '4.9 GB',  ram: 8,  bar: 0.66, tag: 'steady' },
  { name: 'Qwen 3 · 14B',   size: '9.0 GB',  ram: 14, bar: 0.88, tag: 'slow', hot: true },
  { name: 'Gemma 3 · 27B',  size: '16.4 GB', ram: 24, bar: 1.0,  tag: 'experimental', hot: true },
]

const BEATS = [
  {
    ms: 2600, net: true, label: '5G',
    cap: 'Tap install', sub: 'one time only', key: 'store',
  },
  {
    ms: 2500, net: true, label: '5G',
    cap: 'It downloads', sub: 'once — then never again', key: 'dl',
  },
  {
    ms: 2600, net: false, label: '✈ OFF',
    cap: 'Now cut the net', sub: 'airplane mode, no sim, tunnel', key: 'ready',
  },
  {
    ms: 4200, net: false, label: '✈ OFF',
    cap: 'It still works', sub: 'forever, on your phone alone', key: 'chat',
  },
]

const ANSWER =
  'Frozen water — about 1.09 litres, since ice expands when it freezes.'

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
        <span>How it works</span>
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
                      <div className="psz">0.8 GB · everyday AI</div>
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
                  <div className="pnums"><span>0.8 GB</span><span>WI-FI</span></div>
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

      <div className="cap">
        <b>{beat.cap}</b>
        <i>{beat.sub}</i>
      </div>
      <div className="dots">
        {BEATS.map((_, n) => <i key={n} className={n === i ? 'on' : ''} />)}
      </div>
    </div>
  )
}

function Models() {
  return (
    <section className="models" id="models">
      <div className="mh">
        <h2>Model library</h2>
        <span>RAM needed</span>
      </div>
      {MODELS.map((m) => (
        <div className="row" key={m.name}>
          <div>
            <div className="rn">{m.name}</div>
            <div className="rs">{m.size} · {m.tag}</div>
          </div>
          <div>
            <div className="bar">
              <i className={m.hot ? 'hot' : ''} style={{ width: m.bar * 100 + '%' }} />
            </div>
            <div className="need">{m.ram} GB</div>
          </div>
        </div>
      ))}
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
        <div className="brand">LL<em>MOBI</em></div>
        <div className="pill"><span className="pip" />Offline</div>
      </header>

      <div className="split">
        <div className="hero">
          <div className="eyebrow">Local AI — Android</div>
          <h1>AI that<br />works with<span>no internet</span></h1>
          <p className="sub">
            Download a model once. After that it runs on <b>your phone's own chip</b> —
            on a plane, in a tunnel, with the SIM taken out.
          </p>
          <Demo />
          <div className="cta">
            <a className="dl" href="#models">Download for Android</a>
            <div className="fine">
              <span>48 MB</span><span>·</span><span>ANDROID 8+</span><span>·</span><span>NO ACCOUNT</span>
            </div>
          </div>
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
        <div className="fend">
          <span>LLMOBI © 2026</span>
          <b>LOCAL AI, ONE TAP AWAY</b>
        </div>
      </footer>
    </div>
  )
}
