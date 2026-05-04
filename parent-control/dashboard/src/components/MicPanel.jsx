export default function MicPanel({ isActive, onToggle }) {
  return (
    <div className="panel-card">
      <h3>Microphone</h3>
      <p className="panel-desc">
        {isActive
          ? 'Microphone is active — audio is streaming to this dashboard'
          : 'Microphone is off on the client device'}
      </p>
      <button className={`btn ${isActive ? 'btn-danger' : 'btn-success'}`} onClick={onToggle}>
        {isActive ? '🔇 Turn Mic Off' : '🎤 Turn Mic On'}
      </button>
      {isActive && (
        <div className="audio-indicator">
          🔊 Receiving live audio…
        </div>
      )}
    </div>
  )
}
