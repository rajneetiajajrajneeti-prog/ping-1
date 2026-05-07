import { useEffect, useState } from 'react'

function formatDur(ms) {
  if (!ms) return ''
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

export default function ScreenStatusPanel({ screenStatus, unlockPhotos }) {
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    const id = setInterval(() => {
      if (screenStatus?.timestamp)
        setElapsed(Date.now() - screenStatus.timestamp)
    }, 1000)
    return () => clearInterval(id)
  }, [screenStatus?.timestamp])

  const status   = screenStatus?.status || 'unknown'
  const isOn     = status === 'on' || status === 'unlocked'
  const statusLabel = status === 'unlocked' ? 'ON (Unlocked)' : status === 'on' ? 'ON' : status === 'off' ? 'OFF' : '—'

  return (
    <div className="panel-card">
      <div className="panel-header">
        <h3>📺 Screen Status</h3>
        <span className={`status-dot ${isOn ? 'on' : 'off'}`}>{isOn ? '● ON' : '● OFF'}</span>
      </div>

      <div className="screen-stat-row">
        <span className="screen-stat-label">Current</span>
        <span className={`screen-stat-val ${isOn ? 'text-green' : 'text-red'}`}>{statusLabel}</span>
      </div>
      <div className="screen-stat-row">
        <span className="screen-stat-label">{isOn ? 'On for' : 'Off for'}</span>
        <span className="screen-stat-val">{formatDur(elapsed)}</span>
      </div>
      {screenStatus?.prevDurationMs > 0 && (
        <div className="screen-stat-row">
          <span className="screen-stat-label">Before that</span>
          <span className="screen-stat-val">{formatDur(screenStatus.prevDurationMs)}</span>
        </div>
      )}

      {unlockPhotos && unlockPhotos.length > 0 && (
        <>
          <div className="panel-divider" />
          <p className="panel-desc" style={{ marginBottom: 6 }}>
            Unlock photos ({unlockPhotos.length})
          </p>
          <div className="unlock-grid">
            {unlockPhotos.slice(0, 12).map((p, i) => (
              <div key={i} className="unlock-thumb">
                <img
                  src={p.dataUrl}
                  alt="unlock"
                  title={new Date(p.timestamp).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true })}
                />
                <span className="unlock-time">{new Date(p.timestamp).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true })}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
