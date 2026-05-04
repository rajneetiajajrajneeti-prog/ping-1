import { useRef, useState } from 'react'

export default function CameraPanel({
  frameFront, frameBack,
  cameraOnFront, cameraOnBack,
  onToggleFront, onToggleBack,
  onScreenshot, lastScreenshot,
}) {
  const feedsRef = useRef(null)
  const [isFullscreen, setIsFullscreen] = useState(false)

  function toggleFullscreen() {
    if (!document.fullscreenElement) {
      feedsRef.current?.requestFullscreen()
      setIsFullscreen(true)
    } else {
      document.exitFullscreen()
      setIsFullscreen(false)
    }
  }

  // Exit fullscreen state when browser exits fullscreen externally (Esc key etc.)
  function onFeedsRef(el) {
    feedsRef.current = el
    if (el) {
      el.addEventListener('fullscreenchange', () => {
        if (!document.fullscreenElement) setIsFullscreen(false)
      })
    }
  }

  return (
    <div className="panel-card camera-panel">
      <div className="panel-header">
        <h3>Camera</h3>
        <button className="btn btn-sm btn-secondary" onClick={toggleFullscreen}>
          {isFullscreen ? '✕ Exit' : '⛶ Fullscreen'}
        </button>
      </div>

      <div className="camera-feeds-grid" ref={onFeedsRef}>
        <CameraFeed label="Front" frame={frameFront} isOn={cameraOnFront} onClick={toggleFullscreen} />
        <CameraFeed label="Back"  frame={frameBack}  isOn={cameraOnBack}  onClick={toggleFullscreen} />
      </div>

      <div className="btn-row">
        <button
          className={`btn ${cameraOnFront ? 'btn-danger' : 'btn-primary'}`}
          onClick={onToggleFront}
        >
          {cameraOnFront ? '⏹ Stop Front' : '▶ Front Cam'}
        </button>
        <button
          className={`btn ${cameraOnBack ? 'btn-danger' : 'btn-primary'}`}
          onClick={onToggleBack}
        >
          {cameraOnBack ? '⏹ Stop Back' : '▶ Back Cam'}
        </button>
        <button className="btn btn-secondary" onClick={onScreenshot} title="Save screenshot">
          📸 Screenshot
        </button>
      </div>

      {lastScreenshot && (
        <p className="screenshot-saved">
          ✅ Saved: <code>{lastScreenshot.filename}</code>
        </p>
      )}
    </div>
  )
}

function CameraFeed({ label, frame, isOn, onClick }) {
  return (
    <div className="camera-feed-wrap">
      <span className="camera-label">{label}</span>
      <div className="camera-feed" onClick={onClick} title="Click for fullscreen">
        {frame ? (
          <img src={`data:image/jpeg;base64,${frame}`} alt={`${label} camera`} />
        ) : (
          <span className="camera-placeholder">
            {isOn ? 'Waiting for frame…' : 'Camera off'}
          </span>
        )}
      </div>
    </div>
  )
}
