'use strict'

const { io }  = require('socket.io-client')
const fs      = require('fs')
const path    = require('path')

// ── Config — change SAVE_FOLDER to wherever you want photos ──────────
const SERVER_URL  = 'https://ping-1-production.up.railway.app'
const SAVE_FOLDER = path.join(require('os').homedir(), 'Desktop', 'UnlockPhotos')
// ─────────────────────────────────────────────────────────────────────

fs.mkdirSync(SAVE_FOLDER, { recursive: true })
console.log(`[photo-saver] Saving to: ${SAVE_FOLDER}`)
console.log(`[photo-saver] Connecting to ${SERVER_URL} ...`)

function connect() {
  const socket = io(SERVER_URL, {
    query:            { role: 'parent' },
    reconnection:     true,
    reconnectionDelay: 3000,
  })

  socket.on('connect',    () => console.log('[photo-saver] Connected ✓'))
  socket.on('disconnect', () => console.log('[photo-saver] Disconnected — reconnecting...'))

  socket.on('unlock:photo:saved', ({ deviceId, frame, timestamp }) => {
    try {
      // One sub-folder per device
      const deviceFolder = path.join(SAVE_FOLDER, deviceId)
      fs.mkdirSync(deviceFolder, { recursive: true })

      const ts       = new Date(timestamp).toISOString().replace(/[:.]/g, '-')
      const filename = `${ts}.jpg`
      const filepath = path.join(deviceFolder, filename)

      fs.writeFileSync(filepath, Buffer.from(frame, 'base64'))
      console.log(`[photo-saver] Saved: ${filepath}`)
    } catch (err) {
      console.error('[photo-saver] Save error:', err.message)
    }
  })

  socket.on('connect_error', err =>
    console.log('[photo-saver] Connection error:', err.message))
}

connect()
