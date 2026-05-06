function formatDate(ts) {
  return new Date(ts).toLocaleString('en-US', {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true,
  })
}

export default function SMSPanel({ messages, onRefresh }) {
  return (
    <div className="panel-card full-width-panel">
      <div className="panel-header">
        <h3>SMS Messages</h3>
        <button className="btn btn-sm btn-secondary" onClick={onRefresh}>↻ Refresh</button>
      </div>
      {!messages || messages.length === 0 ? (
        <p className="panel-desc">No messages yet — click Refresh</p>
      ) : (
        <div className="sms-list">
          {messages.map((msg, i) => (
            <div key={i} className={`sms-item ${msg.type}`}>
              <div className="sms-header">
                <span className="sms-sender">
                  {msg.type === 'inbox' ? '📩' : '📤'} {msg.address}
                </span>
                <span className="sms-date">{formatDate(msg.date)}</span>
              </div>
              <p className="sms-body">{msg.body}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
