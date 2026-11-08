const { app, BrowserWindow, session, ipcMain } = require('electron');
const path = require('path');
const http = require('http');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
let mainWin = null;

if (!app.requestSingleInstanceLock()) { app.quit(); }

// ---- Customer screen for other devices (an Android tablet/phone opens http://PC-IP:8780/customer) ----
const PORT = 8780;
let lastState = JSON.stringify({ mode: 'idle', store: '', welcome: 'Welcome!', promo: [] });
const clients = new Set();

function startCustomerServer() {
  const srv = http.createServer((req, res) => {
    const url = (req.url || '/').split('?')[0];
    if (url === '/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no' });
      res.write('retry: 2000\n\n');
      res.write('data: ' + lastState + '\n\n');
      clients.add(res);
      req.on('close', () => clients.delete(res));
      return;
    }
    if (url === '/' || url === '/customer' || url === '/customer.html') {
      try {
        const html = fs.readFileSync(path.join(__dirname, 'www', 'customer.html'));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(html);
      } catch (e) { res.writeHead(500); res.end('customer.html not found'); }
      return;
    }
    res.writeHead(404); res.end('Not found');
  });
  srv.on('error', e => console.error('Customer screen server error:', e.message));
  srv.listen(PORT, '0.0.0.0');
  setInterval(() => { clients.forEach(c => { try { c.write(': ping\n\n'); } catch (e) {} }); }, 15000);
}

ipcMain.on('tp-customer-state', (e, json) => {
  lastState = String(json);
  clients.forEach(c => { try { c.write('data: ' + lastState + '\n\n'); } catch (err) {} });
});
function lanIps() {
  const ips = [];
  const nets = os.networkInterfaces();
  Object.keys(nets).forEach(n => (nets[n] || []).forEach(a => { if (a.family === 'IPv4' && !a.internal) ips.push(a.address); }));
  return ips;
}
ipcMain.handle('tp-server-info', () => ({ port: PORT, ips: lanIps() }));

// ---- Scanner receiver: barcode and product-image requests from the Android scanner app ----
const SCAN_PORT = 8765;
let scanSrv = null, scanCode = '', scanFails = 0, scanLockUntil = 0;
const imageQueries = new Map();
const scanInfo = () => ({ running: !!scanSrv, port: SCAN_PORT, ips: lanIps() });

function scanReply(res, status, body) {
  res.writeHead(status, {
    'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type'
  });
  res.end(body);
}
function askPos(action, data) {
  if (!mainWin || mainWin.isDestroyed() || mainWin.webContents.isDestroyed()) return Promise.reject(new Error('TindaPOS is not ready'));
  const encoded = JSON.stringify(data || {}).replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return mainWin.webContents.executeJavaScript(`window.tpScannerImageRequest(${JSON.stringify(action)},${encoded})`, true);
}
function readScannerBody(req, res, callback) {
  let body = '';
  req.on('data', d => {
    body += d;
    if (body.length > 5 * 1024 * 1024) { scanReply(res, 413, '{"ok":false,"error":"Request too large"}'); req.destroy(); }
  });
  req.on('end', () => {
    try { callback(JSON.parse(body)); }
    catch (e) { scanReply(res, 400, '{"ok":false,"error":"Invalid JSON"}'); }
  });
}
function onScanRequest(req, res) {
  const url = (req.url || '').split('?')[0];
  if (req.method === 'OPTIONS') return scanReply(res, 204, '');
  if (req.method !== 'POST' || !['/scan', '/image', '/find', '/add'].includes(url)) return scanReply(res, 404, '{}');
  if (Date.now() < scanLockUntil) return scanReply(res, 429, '{"ok":false}');
  readScannerBody(req, res, async j => {
    const sent = Buffer.from(String(j.pairCode || ''));
    const want = Buffer.from(scanCode);
    if (sent.length !== want.length || !crypto.timingSafeEqual(sent, want)) {
      if (++scanFails >= 10) { scanLockUntil = Date.now() + 60000; scanFails = 0; }
      return scanReply(res, 401, '{"ok":false,"error":"Invalid pairing code"}');
    }
    scanFails = 0;
    if (url === '/scan') {
      const bc = String(j.barcode || '').trim();
      if (!bc || bc.length > 128) return scanReply(res, 400, '{"ok":false,"error":"Invalid barcode"}');
      scanReply(res, 200, '{"ok":true}');
      if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('tp-scan', bc);
      return;
    }
    try {
      if (url === '/image') {
        const image = String(j.image || '');
        if (!/^data:image\/(jpeg|png|webp);base64,/i.test(image) || image.length > 2 * 1024 * 1024)
          return scanReply(res, 400, '{"ok":false,"error":"Invalid or oversized image"}');
        const result = await askPos('image', { image });
        const queryId = crypto.randomUUID();
        imageQueries.set(queryId, { image, searched: false, createdAt: Date.now() });
        for (const [id, q] of imageQueries) if (Date.now() - q.createdAt > 15 * 60 * 1000) imageQueries.delete(id);
        return scanReply(res, 200, JSON.stringify({ queryId, suggestions: result.suggestions || [], hint: result.hint || '' }));
      }
      if (url === '/find') {
        const q = String(j.q || '').trim().slice(0, 100);
        if (!q) return scanReply(res, 400, '{"ok":false,"error":"Search text is required"}');
        const query = imageQueries.get(String(j.queryId || ''));
        if (query) query.searched = true;
        const result = await askPos('find', { q });
        return scanReply(res, 200, JSON.stringify({ suggestions: result.suggestions || [] }));
      }
      const id = String(j.id || '');
      if (!id) return scanReply(res, 400, '{"ok":false,"error":"Product is required"}');
      const query = imageQueries.get(String(j.queryId || ''));
      const result = await askPos('add', {
        id, vid: String(j.vid || ''),
        learnImage: !!(query && query.searched), image: query && query.searched ? query.image : ''
      });
      if (!result || !result.ok) return scanReply(res, result && result.error ? 403 : 404, JSON.stringify({ ok: false, error: result && result.error ? result.error : 'Product not found' }));
      imageQueries.delete(String(j.queryId || ''));
      return scanReply(res, 200, JSON.stringify({ ok: true, learned: !!result.learned }));
    } catch (e) {
      const message = String((e && e.message) || 'TindaPOS could not process the image scan').slice(0, 200);
      return scanReply(res, 500, JSON.stringify({ ok: false, error: message }));
    }
  });
}
ipcMain.handle('tp-scanner-start', (e, code) => {
  scanCode = String(code || '').trim();
  if (!scanCode) throw new Error('Pairing code missing');
  if (scanSrv) return scanInfo();
  return new Promise((resolve, reject) => {
    const srv = http.createServer(onScanRequest);
    srv.once('error', err => reject(new Error('Could not open port ' + SCAN_PORT + ': ' + err.message)));
    srv.listen(SCAN_PORT, '0.0.0.0', () => { scanSrv = srv; resolve(scanInfo()); });
  });
});
ipcMain.handle('tp-scanner-stop', () => { if (scanSrv) { scanSrv.close(); scanSrv = null; } return scanInfo(); });
ipcMain.handle('tp-scanner-status', () => scanInfo());

function createWindow() {
  const win = new BrowserWindow({
    width: 1280, height: 800, show: false, autoHideMenuBar: true, title: 'TindaPOS.ph', icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, preload: path.join(__dirname, 'preload.js') }
  });
  mainWin = win;
  win.once('ready-to-show', () => { win.maximize(); win.show(); });
  // customer display (customer.html) and print windows
  win.webContents.setWindowOpenHandler(() => ({ action: 'allow', overrideBrowserWindowOptions: { autoHideMenuBar: true } }));
  win.loadFile(path.join(__dirname, 'www', 'index.html'));
}

app.whenReady().then(() => {
  // allow camera / microphone-free media for the camera scanner
  session.defaultSession.setPermissionRequestHandler((wc, permission, cb) => cb(['media', 'fullscreen'].includes(permission)));
  startCustomerServer();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('second-instance', () => {
  const w = BrowserWindow.getAllWindows()[0];
  if (w) { if (w.isMinimized()) w.restore(); w.focus(); }
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
