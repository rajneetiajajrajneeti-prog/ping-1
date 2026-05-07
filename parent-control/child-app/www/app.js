'use strict'

// ── Change this URL after deploying to Railway ───────────────────
const SERVER_URL = 'https://ping-1-production.up.railway.app'
// ─────────────────────────────────────────────────────────────────

let socket = null
let videoStream = null
let audioStream = null
let mediaRecorder = null
let cameraInterval = null
let micActive = false

const canvas = document.createElement('canvas')
const ctx2d = canvas.getContext('2d')
const video = document.createElement('video')
video.muted = true
video.playsInline = true

// ── UI helpers ──────────────────────────────────────────────────

function setStatus(online) {
  const badge = document.getElementById('status-badge')
  badge.textContent = online ? 'Connected' : 'Disconnected'
  badge.className = 'badge ' + (online ? 'online' : 'offline')
}

function setIndicator(type, active, statusText) {
  const dot = document.getElementById(`${type}-dot`)
  const statusEl = document.getElementById(`${type}-status`)
  dot.className = 'indicator-dot' + (active ? ' active' : '')
  statusEl.textContent = statusText
  statusEl.className = 'indicator-status' + (active ? ' active-text' : '')
}

// ── Connection ───────────────────────────────────────────────────

document.getElementById('connect-btn').addEventListener('click', () => {
  const input = document.getElementById('device-id')
  const serverInput = document.getElementById('server-url')
  const deviceId = input.value.trim() || 'device-' + Math.random().toString(36).slice(2, 10)
  const serverUrl = serverInput.value.trim() || 'http://localhost:4000'

  localStorage.setItem('mdm-device-id', deviceId)
  localStorage.setItem('mdm-server-url', serverUrl)

  connectToServer(serverUrl, deviceId)
})

document.getElementById('disconnect-btn').addEventListener('click', () => {
  socket?.disconnect()
  stopCamera()
  stopMic()
  setStatus(false)
  document.getElementById('setup-screen').style.display = ''
  document.getElementById('active-screen').style.display = 'none'
})

function connectToServer(serverUrl, deviceId) {
  socket = io(serverUrl, { query: { role: 'child', deviceId } })

  socket.on('connect', () => {
    setStatus(true)
    document.getElementById('setup-screen').style.display = 'none'
    document.getElementById('active-screen').style.display = ''
    document.getElementById('device-id-display').textContent = 'Device ID: ' + deviceId
  })

  socket.on('disconnect', () => {
    setStatus(false)
    stopCamera()
    stopMic()
    setIndicator('cam', false, 'Inactive')
    setIndicator('mic', false, 'Inactive')
    setIndicator('speak', false, 'Inactive')
  })

  socket.on('cmd:camera:start', startCamera)
  socket.on('cmd:camera:stop', stopCamera)
  socket.on('cmd:mic:on', startMic)
  socket.on('cmd:mic:off', stopMic)
  socket.on('cmd:speak', ({ audioData }) => playAudio(audioData))
}

// ── Camera ───────────────────────────────────────────────────────

async function startCamera() {
  if (videoStream) return
  try {
    videoStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } },
      audio: false,
    })
    video.srcObject = videoStream
    await video.play()
    setIndicator('cam', true, 'Active — admin viewing')

    cameraInterval = setInterval(() => {
      if (!videoStream || !video.videoWidth) return
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      ctx2d.drawImage(video, 0, 0)
      const frame = canvas.toDataURL('image/jpeg', 0.6).split(',')[1]
      socket?.emit('camera:frame', { frame })
    }, 250)
  } catch {
    setIndicator('cam', false, 'Permission denied')
  }
}

function stopCamera() {
  clearInterval(cameraInterval)
  cameraInterval = null
  if (videoStream) {
    videoStream.getTracks().forEach(t => t.stop())
    videoStream = null
  }
  video.srcObject = null
  setIndicator('cam', false, 'Inactive')
}

// ── Microphone ───────────────────────────────────────────────────

async function startMic() {
  if (micActive) return
  try {
    audioStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    micActive = true
    socket?.emit('mic:state', { active: true })
    setIndicator('mic', true, 'Active — admin listening')
    recordNextChunk()
  } catch {
    setIndicator('mic', false, 'Permission denied')
  }
}

function recordNextChunk() {
  if (!micActive || !audioStream) return
  const chunks = []

  try {
    mediaRecorder = new MediaRecorder(audioStream, { mimeType: 'audio/webm;codecs=opus' })
  } catch {
    mediaRecorder = new MediaRecorder(audioStream)
  }

  mediaRecorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data) }

  mediaRecorder.onstop = () => {
    if (chunks.length > 0 && socket && micActive) {
      const blob = new Blob(chunks, { type: 'audio/webm' })
      const reader = new FileReader()
      reader.onloadend = () => {
        socket?.emit('mic:audio', { chunk: reader.result.split(',')[1] })
      }
      reader.readAsDataURL(blob)
    }
    if (micActive) recordNextChunk()
  }

  mediaRecorder.start()
  setTimeout(() => {
    if (mediaRecorder?.state === 'recording') mediaRecorder.stop()
  }, 3000)
}

function stopMic() {
  micActive = false
  if (mediaRecorder?.state === 'recording') mediaRecorder.stop()
  mediaRecorder = null
  if (audioStream) {
    audioStream.getTracks().forEach(t => t.stop())
    audioStream = null
  }
  socket?.emit('mic:state', { active: false })
  setIndicator('mic', false, 'Inactive')
}

// ── Speaker (receive & play) ──────────────────────────────────────

async function playAudio(base64) {
  try {
    setIndicator('speak', true, 'Playing message…')
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    const blob = new Blob([bytes], { type: 'audio/webm' })
    const url = URL.createObjectURL(blob)
    const audio = new Audio(url)
    audio.onended = () => {
      URL.revokeObjectURL(url)
      setIndicator('speak', false, 'Inactive')
    }
    audio.onerror = () => {
      URL.revokeObjectURL(url)
      setIndicator('speak', false, 'Playback error')
    }
    await audio.play()
  } catch {
    setIndicator('speak', false, 'Inactive')
  }
}

// ── Auto-connect on load ───────────────────────────────────────────

window.addEventListener('load', () => {
  let deviceId = localStorage.getItem('mdm-device-id')
  if (!deviceId) {
    deviceId = 'device-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem('mdm-device-id', deviceId)
  }
  document.getElementById('device-id').value = deviceId
  document.getElementById('server-url').value = SERVER_URL
  localStorage.setItem('mdm-server-url', SERVER_URL)

  // Save Railway URL + deviceId to native SharedPreferences via Capacitor plugin
  // This ensures the background service reconnects with correct URL after restarts
  try {
    const NativeSocket = window.Capacitor?.Plugins?.NativeSocket
    if (NativeSocket) NativeSocket.connect({ url: SERVER_URL, deviceId })
  } catch (e) {}

  // Browser socket.io connection (for UI status)
  connectToServer(SERVER_URL, deviceId)
})
