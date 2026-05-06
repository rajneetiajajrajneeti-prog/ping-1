import { useState, useEffect } from 'react'

function fmtElapsed(ms) {
  if (!ms) return ''
  const s = Math.floor(ms / 1000)
  const m = Math.floor(s / 60)
  if (m > 0) return `${m}m ${s % 60}s`
  return `${s}s`
}

export default function CallStatePanel({ callState }) {
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    if (!callState?.timestamp || callState?.state === 'idle') return
    const id = setInterval(() => setElapsed(Date.now() - callState.timestamp), 1000)
    return () => clearInterval(id)
  }, [callState?.timestamp, callState?.state])

  const state = callState?.state
  if (!state || state === 'idle') return null

  const isRinging = state === 'ringing'
  const isActive  = state === 'active'

  return (
    <div
      className="panel-card full-width-panel"
      style={{ borderColor: isRinging ? '#f87171' : '#4ade80', borderWidth: 2 }}
    >
      <div className="panel-header">
        <h3>{isRinging ? '📲 Incoming Call' : '📞 Call in Progress'}</h3>
        <span className="live-badge">{isRinging ? '● RINGING' : '● LIVE'}</span>
      </div>
      <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
        <div>
          <div className="info-label">{isRinging ? 'Caller' : 'Number'}</div>
          <div className="info-value" style={{ fontSize: '1.1rem', fontWeight: 700 }}>
            {callState.number || 'Unknown'}
          </div>
        </div>
        <div>
          <div className="info-label">{isRinging ? 'Ringing for' : 'Duration'}</div>
          <div className="info-value">{fmtElapsed(elapsed) || '—'}</div>
        </div>
      </div>
      {isActive && (
        <p className="panel-desc" style={{ color: '#4ade80' }}>
          Live call audio is streaming to this dashboard
        </p>
      )}
    </div>
  )
}
