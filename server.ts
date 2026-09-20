import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive',
};

function getApkPath(): string | null {
  const candidates = [
    path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    path.join(__dirname, '.build-outputs', 'app-debug.apk'),
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost:3000'}`);
  let pathname = parsedUrl.pathname;

  // Handle health check
  if (pathname === '/health' || pathname === '/_health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', uptime: process.uptime() }));
    return;
  }

  // Handle API status
  if (pathname === '/api/status') {
    const apkPath = getApkPath();
    const hasApk = apkPath !== null;
    let apkSize = 0;
    if (apkPath) {
      try {
        apkSize = fs.statSync(apkPath).size;
      } catch {
        // ignore
      }
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(
      JSON.stringify({
        appName: 'HeadMotionMouse',
        framework: 'Android + Web Simulator',
        apkAvailable: hasApk,
        apkSize,
        apkDownloadUrl: '/app-debug.apk',
      })
    );
    return;
  }

  // Handle APK download
  if (pathname === '/app-debug.apk' || pathname === '/download/apk' || pathname === '/headmotionmouse.apk') {
    const apkPath = getApkPath();
    if (!apkPath) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK not found. Please build the project first.');
      return;
    }

    try {
      const stat = fs.statSync(apkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="HeadMotionMouse-debug.apk"',
      });
      const stream = fs.createReadStream(apkPath);
      stream.pipe(res);
      return;
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('Error streaming APK: ' + String(err));
      return;
    }
  }

  // Serve root / or /index.html from test_simulator/index.html
  if (pathname === '/' || pathname === '/index.html') {
    const htmlPath = path.join(__dirname, 'test_simulator', 'index.html');
    if (fs.existsSync(htmlPath)) {
      try {
        let content = fs.readFileSync(htmlPath, 'utf8');
        // Inject APK download banner if not already present
        if (!content.includes('id="apk-download-btn"')) {
          const bannerHtml = `
            <a id="apk-download-btn" href="/app-debug.apk" style="display:inline-flex;align-items:center;gap:8px;background:#00e5ff;color:#101014;font-weight:700;font-size:0.82rem;padding:6px 14px;border-radius:20px;text-decoration:none;transition:opacity 0.2s;" title="Download Android APK">
              <span>⬇ Download Android APK</span>
            </a>`;
          content = content.replace(
            '<div class="header-actions">',
            `<div class="header-actions">${bannerHtml}`
          );
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(content);
        return;
      } catch (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end('Error loading simulator: ' + String(err));
        return;
      }
    }
  }

  // Serve static files from test_simulator or public
  let relativePath = pathname.startsWith('/') ? pathname.slice(1) : pathname;
  let filePath = path.join(__dirname, 'test_simulator', relativePath);
  if (!fs.existsSync(filePath)) {
    filePath = path.join(__dirname, relativePath);
  }

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(filePath).pipe(res);
    return;
  }

  // Fallback to simulator index.html for SPA routes
  const fallbackHtml = path.join(__dirname, 'test_simulator', 'index.html');
  if (fs.existsSync(fallbackHtml)) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    fs.createReadStream(fallbackHtml).pipe(res);
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not Found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[Server] HeadMotionMouse dev server running on http://0.0.0.0:${PORT}`);
});
