import { useState } from 'react'

export default function LocationPanel({ location, isOn, onStart, onStop }) {
  const [copied, setCopied] = useState(false)

  const mapsLink = location
    ? `https://maps.google.com/?q=${location.lat},${location.lng}`
    : null

  function handleGet() {
    if (isOn) {
      onStop()
    } else {
      onStart()
    }
  }

  function copyLink() {
    if (!mapsLink) return
    navigator.clipboard.writeText(mapsLink).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  function formatTime(ts) {
    if (!ts) return ''
    return new Date(ts).toLocaleTimeString()
  }

  return (
    <div className="panel-card location-panel">
      <div className="panel-header">
        <h3>📍 Live Location</h3>
        {isOn && !location && <span style={{ fontSize: '0.78rem', color: '#94a3b8' }}>Waiting for GPS…</span>}
        {location && <span style={{ fontSize: '0.72rem', color: '#64748b' }}>Updated {formatTime(location.timestamp)}</span>}
      </div>

      {location && (
        <div className="location-link-box">
          <a
            href={mapsLink}
            target="_blank"
            rel="noopener noreferrer"
            className="maps-link"
          >
            {mapsLink}
          </a>
          <button className="btn btn-sm btn-secondary copy-btn" onClick={copyLink}>
            {copied ? '✓ Copied' : 'Copy'}
          </button>
        </div>
      )}

      {location && (
        <div className="location-meta">
          <span>Accuracy: ±{Math.round(location.accuracy ?? 0)}m</span>
          <span>Provider: {location.provider || '—'}</span>
        </div>
      )}

      {!location && !isOn && (
        <p className="location-placeholder">Press Get Location to fetch the device's current GPS coordinates.</p>
      )}

      <div className="btn-row">
        <button className="btn btn-primary" onClick={handleGet}>
          {isOn ? (location ? '🔄 Refresh' : '⏳ Waiting…') : '📍 Get Location'}
        </button>
        {isOn && location && (
          <button className="btn btn-danger" onClick={onStop}>Stop</button>
        )}
      </div>
    </div>
  )
}
