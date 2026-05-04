export default function InfoPanel({ info, battery }) {
  if (!info && !battery) {
    return (
      <div className="panel-card">
        <h3>Device Info</h3>
        <p className="panel-desc">Waiting for device to send info…</p>
      </div>
    )
  }

  const batteryIcon = battery
    ? battery.charging ? '⚡' : battery.level > 20 ? '🔋' : '🪫'
    : null

  return (
    <div className="panel-card">
      <h3>Device Info</h3>
      <div className="info-grid">
        {battery && (
          <div className="info-row battery-row">
            <span className="info-label">Battery</span>
            <span className="info-value">
              {batteryIcon} {battery.level}%
              {battery.charging && <span className="charging-tag">Charging</span>}
            </span>
            <div className="battery-bar">
              <div
                className="battery-fill"
                style={{
                  width: `${battery.level}%`,
                  background: battery.level > 20 ? '#4ade80' : '#f87171',
                }}
              />
            </div>
          </div>
        )}
        {info?.model && (
          <div className="info-row">
            <span className="info-label">Model</span>
            <span className="info-value">{info.manufacturer} {info.model}</span>
          </div>
        )}
        {info?.androidVersion && (
          <div className="info-row">
            <span className="info-label">Android</span>
            <span className="info-value">{info.androidVersion} (SDK {info.sdkInt})</span>
          </div>
        )}
        {info?.androidId && (
          <div className="info-row">
            <span className="info-label">Device ID</span>
            <span className="info-value mono">{info.androidId}</span>
          </div>
        )}
      </div>
    </div>
  )
}
