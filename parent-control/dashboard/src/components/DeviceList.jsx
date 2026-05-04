export default function DeviceList({ devices, selected, onSelect }) {
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
          title={dev.online ? dev.deviceId : 'Device offline'}
        >
          <span className="dot" />
          <span className="device-id">{dev.deviceId}</span>
          <span className="device-status">{dev.online ? 'Online' : 'Offline'}</span>
        </li>
      ))}
    </ul>
  )
}
