function formatDuration(sec) {
  if (!sec) return '—'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return m > 0 ? `${m}m ${s}s` : `${s}s`
}

function formatDate(ts) {
  return new Date(ts).toLocaleString()
}

const typeIcon = { incoming: '📲', outgoing: '📤', missed: '❌' }

export default function CallLogsPanel({ logs, onRefresh }) {
  return (
    <div className="panel-card full-width-panel">
      <div className="panel-header">
        <h3>Call Logs</h3>
        <button className="btn btn-sm btn-secondary" onClick={onRefresh}>↻ Refresh</button>
      </div>
      {!logs || logs.length === 0 ? (
        <p className="panel-desc">No call logs yet — click Refresh</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Name</th>
                <th>Number</th>
                <th>Duration</th>
                <th>Date</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((log, i) => (
                <tr key={i} className={log.type === 'missed' ? 'row-missed' : ''}>
                  <td>{typeIcon[log.type] || '📞'} {log.type}</td>
                  <td>{log.name || '—'}</td>
                  <td className="mono">{log.number}</td>
                  <td>{formatDuration(log.duration)}</td>
                  <td>{formatDate(log.date)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
