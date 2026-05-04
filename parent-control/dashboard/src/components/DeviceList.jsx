import { useState } from 'react'

export default function DeviceList({ devices, selected, onSelect, deviceNames, onRename }) {
  const [editing, setEditing] = useState(null)
  const [nameInput, setNameInput] = useState('')

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

  if (devices.length === 0) {
    return <p className="no-devices">No devices connected yet</p>
  }

  return (
    <ul className="device-list">
      {devices.map(dev => (
        <li
          key={dev.deviceId}
          className={[
            'device-item',
            dev.online ? 'online' : 'offline',
            selected === dev.deviceId ? 'active' : '',
          ].join(' ')}
          onClick={() => dev.online && onSelect(dev.deviceId)}
          title={dev.deviceId}
        >
          <span className="dot" />

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

          <button
            className="rename-btn"
            onClick={e => startEdit(e, dev)}
            title="Rename device"
          >
            ✏
          </button>

          <span className="device-status">{dev.online ? 'Online' : 'Offline'}</span>
        </li>
      ))}
    </ul>
  )
}
