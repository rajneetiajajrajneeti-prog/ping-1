export default function CameraPanel({ frame, isOn, onToggle, onScreenshot, lastScreenshot }) {
  return (
    <div className="panel-card camera-panel">
      <h3>Camera Feed</h3>
      <div className="camera-feed">
        {frame ? (
          <img src={`data:image/jpeg;base64,${frame}`} alt="Live camera feed" />
        ) : (
          <span className="camera-placeholder">
            {isOn ? 'Waiting for first frame…' : 'Camera is off'}
          </span>
        )}
      </div>
      <div className="btn-row">
        <button className={`btn ${isOn ? 'btn-danger' : 'btn-primary'}`} onClick={onToggle}>
          {isOn ? '⏹ Stop Camera' : '▶ Start Camera'}
        </button>
        <button className="btn btn-secondary" onClick={onScreenshot} title="Save screenshot to Desktop">
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
