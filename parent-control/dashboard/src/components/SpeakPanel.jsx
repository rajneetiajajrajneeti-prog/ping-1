import { useRef, useState } from 'react'

export default function SpeakPanel({ onSpeak }) {
  const [recording, setRecording] = useState(false)
  const recorderRef = useRef(null)
  const chunksRef = useRef([])
  const streamRef = useRef(null)

  async function startRecord() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      streamRef.current = stream
      chunksRef.current = []

      const recorder = new MediaRecorder(stream)
      recorderRef.current = recorder

      recorder.ondataavailable = e => {
        if (e.data.size > 0) chunksRef.current.push(e.data)
      }

      recorder.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
        const reader = new FileReader()
        reader.onloadend = () => {
          const base64 = reader.result.split(',')[1]
          onSpeak(base64)
        }
        reader.readAsDataURL(blob)
        stream.getTracks().forEach(t => t.stop())
        streamRef.current = null
      }

      recorder.start()
      setRecording(true)
    } catch {
      alert('Microphone access denied. Please allow mic access in your browser.')
    }
  }

  function stopRecord() {
    recorderRef.current?.stop()
    setRecording(false)
  }

  return (
    <div className="panel-card">
      <h3>Speak to Device</h3>
      <p className="panel-desc">
        Record a voice message and play it through the client's speaker
      </p>
      {recording ? (
        <>
          <button className="btn btn-danger" onClick={stopRecord}>
            ⏹ Stop &amp; Send
          </button>
          <div className="recording-indicator">● Recording…</div>
        </>
      ) : (
        <button className="btn btn-primary" onClick={startRecord}>
          🎙 Start Recording
        </button>
      )}
    </div>
  )
}
