import { useRef, useState } from 'react'

export default function ScreenPanel({ frame, isOn, onStart, onStop }) {
  const feedRef = useRef(null)
  const [fullscreen, setFullscreen] = useState(false)

  function toggleFullscreen() {
    if (!document.fullscreenElement) {
      feedRef.current?.requestFullscreen()
      setFullscreen(true)
    } else {
      document.exitFullscreen()
      setFullscreen(false)
    }
  }

  function onRef(el) {
    feedRef.current = el
    if (el) el.addEventListener('fullscreenchange', () => {
      if (!document.fullscreenElement) setFullscreen(false)
    })
  }

  return (
    <div className="panel-card screen-panel">
      <div className="panel-header">
        <h3>📱 Live Screen</h3>
        <button className="btn btn-sm btn-secondary" onClick={toggleFullscreen}>
          {fullscreen ? '✕ Exit' : '⛶ Fullscreen'}
        </button>
      </div>

      <div className="screen-feed" ref={onRef} onClick={toggleFullscreen} title="Click to fullscreen">
        {frame ? (
          <img src={`data:image/jpeg;base64,${frame}`} alt="Phone screen" />
        ) : (
          <div className="screen-placeholder">
            {isOn
              ? '⏳ Waiting — approve the dialog on the phone'
              : '📵 Screen off — press Start to mirror'}
          </div>
        )}
      </div>

      <div className="btn-row">
        {isOn ? (
          <button className="btn btn-danger" onClick={onStop}>⏹ Stop Screen</button>
        ) : (
          <button className="btn btn-primary" onClick={onStart}>▶ Start Screen</button>
        )}
        {isOn && <span className="live-badge">● LIVE</span>}
      </div>

      {isOn && !frame && (
        <p className="screen-hint">
          A dialog will appear on the phone — tap <strong>Start now</strong> to allow screen capture.
        </p>
      )}
    </div>
  )
}
