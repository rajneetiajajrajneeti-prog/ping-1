import { useRef, useState } from 'react'

export default function SpeakPanel({ onSpeak, onLiveSpeakStart, onLiveSpeakStop, liveSpeakActive }) {
  const [recording, setRecording] = useState(false)
  const recorderRef = useRef(null)
  const chunksRef   = useRef([])
  const streamRef   = useRef(null)

  async function startRecord() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      streamRef.current = stream
      chunksRef.current = []
      const recorder = new MediaRecorder(stream)
      recorderRef.current = recorder
      recorder.ondataavailable = e => { if (e.data.size > 0) chunksRef.current.push(e.data) }
      recorder.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
        const reader = new FileReader()
        reader.onloadend = () => onSpeak(reader.result.split(',')[1])
        reader.readAsDataURL(blob)
        stream.getTracks().forEach(t => t.stop())
        streamRef.current = null
      }
      recorder.start()
      setRecording(true)
    } catch { alert('Microphone access denied') }
  }

  function stopRecord() { recorderRef.current?.stop(); setRecording(false) }

  return (
    <div className="panel-card">
      <h3>Speaker</h3>

      {/* Live Speak */}
      <div className="speak-section">
        <p className="panel-desc">Live — speak and phone plays instantly</p>
        {liveSpeakActive ? (
          <div className="live-speak-active">
            <div className="recording-indicator">🔴 Live speaking…</div>
            <button className="btn btn-danger" onClick={onLiveSpeakStop}>⏹ Stop Live</button>
          </div>
        ) : (
          <button className="btn btn-success" onClick={onLiveSpeakStart}
            disabled={recording}>
            🎙 Live Speak
          </button>
        )}
      </div>

      <div className="speak-divider" />

      {/* Record & Send */}
      <div className="speak-section">
        <p className="panel-desc">Record a clip and send to phone</p>
        {recording ? (
          <div className="live-speak-active">
            <div className="recording-indicator">● Recording…</div>
            <button className="btn btn-danger" onClick={stopRecord}>⏹ Stop &amp; Send</button>
          </div>
        ) : (
          <button className="btn btn-primary" onClick={startRecord}
            disabled={liveSpeakActive}>
            🎤 Record &amp; Send
          </button>
        )}
      </div>
    </div>
  )
}
