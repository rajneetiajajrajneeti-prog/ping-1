import { useState } from 'react'

function timeAgo(ts) {
  if (!ts) return null
  const secs = Math.floor((Date.now() - ts) / 1000)
  if (secs < 60)  return `${secs}s ago`
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`
  return `${Math.floor(secs / 3600)}h ago`
}

export default function DeviceList({ devices, selected, onSelect, deviceNames, onRename, onRefresh }) {
  const [editing,   setEditing]   = useState(null)
  const [nameInput, setNameInput] = useState('')
  const [refreshing, setRefreshing] = useState(false)

  function startEdit(e, dev) {
    e.stopPropagation()
    setEditing(dev.deviceId)
    setNameInput(deviceNames?.[dev.deviceId] || '')
  }

  function saveEdit(deviceId) {
    const trimmed = nameInput.trim()
    if (trimmed) onRename?.(deviceId, trimmed)
    setEditing(null)
  }

  async function handleRefresh(e) {
    e.stopPropagation()
    setRefreshing(true)
    onRefresh?.()
    setTimeout(() => setRefreshing(false), 2000)
  }

  if (devices.length === 0) {
    return (
      <div>
        <p className="no-devices">No devices connected yet</p>
        <button className="refresh-btn" onClick={handleRefresh} disabled={refreshing}>
          {refreshing ? 'Refreshing…' : '🔄 Refresh'}
        </button>
      </div>
    )
  }

  return (
    <>
      <ul className="device-list">
        {devices.map(dev => (
          <li
            key={dev.deviceId}
            className={[
              'device-item',
              dev.online ? 'online' : 'offline',
              selected === dev.deviceId ? 'active' : '',
            ].join(' ')}
            onClick={() => onSelect(dev.deviceId)}
            title={dev.deviceId}
          >
            <span className="dot" />

            <div className="device-info">
              {editing === dev.deviceId ? (
                <input
                  className="device-name-input"
                  value={nameInput}
                  onChange={e => setNameInput(e.target.value)}
                  onBlur={() => saveEdit(dev.deviceId)}
                  onKeyDown={e => {
                    if (e.key === 'Enter') saveEdit(dev.deviceId)
                    if (e.key === 'Escape') setEditing(null)
                  }}
                  autoFocus
                  onClick={e => e.stopPropagation()}
                />
              ) : (
                <span className="device-id">
                  {deviceNames?.[dev.deviceId] || dev.deviceId}
                </span>
              )}

              {!dev.online && dev.lastSeen && (
                <span className="last-seen">Last seen: {timeAgo(dev.lastSeen)}</span>
              )}
            </div>

            <div className="device-actions">
              <button
                className="rename-btn"
                onClick={e => startEdit(e, dev)}
                title="Rename device"
              >✏</button>
              <span className="device-status">{dev.online ? 'Online' : 'Offline'}</span>
            </div>
          </li>
        ))}
      </ul>

      <button className="refresh-btn" onClick={handleRefresh} disabled={refreshing}>
        {refreshing ? 'Refreshing…' : '🔄 Refresh Status'}
      </button>
    </>
  )
}
