function formatDur(ms) {
  if (!ms || ms < 1000) return '<1s'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

function appLabel(pkg) {
  const parts = pkg.split('.')
  return parts[parts.length - 1] || pkg
}

export default function RecentAppsPanel({ recentApps, onRefresh }) {
  if (recentApps?.error === 'usage_access_not_granted') {
    return (
      <div className="panel-card full-width-panel">
        <div className="panel-header">
          <h3>📱 Recent Apps</h3>
          <button className="btn btn-sm btn-secondary" onClick={onRefresh}>↻ Refresh</button>
        </div>
        <div className="usage-grant-msg">
          <p>Usage Access permission needed.</p>
          <p>On the phone: <strong>Settings → Special App Access → Usage Access → MDM Agent → Allow</strong></p>
          <p style={{ fontSize: '0.75rem', color: '#64748b' }}>Then click Refresh above.</p>
        </div>
      </div>
    )
  }

  const apps = recentApps?.apps || []

  return (
    <div className="panel-card full-width-panel">
      <div className="panel-header">
        <h3>📱 Recent Apps (24h)</h3>
        <button className="btn btn-sm btn-secondary" onClick={onRefresh}>↻ Refresh</button>
      </div>
      {apps.length === 0 ? (
        <p className="panel-desc">No data yet — click Refresh</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>#</th>
                <th>App</th>
                <th>Package</th>
                <th>Last Used</th>
                <th>Time Used</th>
              </tr>
            </thead>
            <tbody>
              {apps.map((app, i) => (
                <tr key={i}>
                  <td style={{ color: '#64748b' }}>{i + 1}</td>
                  <td><strong>{appLabel(app.package)}</strong></td>
                  <td className="mono" style={{ fontSize: '0.72rem', color: '#64748b' }}>{app.package}</td>
                  <td>{new Date(app.lastUsed).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true })}</td>
                  <td>{formatDur(app.totalMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
