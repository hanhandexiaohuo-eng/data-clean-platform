// ============================================================
// DataClean 恶意文件扫描网关
// ------------------------------------------------------------
// 职责：把 DataClean 的 HTTP 扫描契约转换为 ClamAV clamd 的 INSTREAM 协议。
//   POST /scan  multipart/form-data（字段 file）→ 200 {"clean":true|false}
//   GET  /health → clamd PING，正常 200 {"status":"UP"}
//
// 安全约束：
//   - 仅监听容器内部端口，不对公网发布；
//   - 不记录文件名、文件内容、扫描地址与令牌；
//   - 非 200 / 超时 / 协议异常统一返回 503，由后端按 fail-closed 拒绝上传。
// ============================================================
import http from 'node:http';
import net from 'node:net';
import Busboy from 'busboy';

const PORT = Number(process.env.PORT || 8080);
const CLAMD_HOST = process.env.CLAMD_HOST || 'clamav';
const CLAMD_PORT = Number(process.env.CLAMD_PORT || 3310);
const SCAN_TOKEN = process.env.SCAN_TOKEN || '';
const MAX_FILE_BYTES = Number(process.env.MAX_FILE_BYTES || 32 * 1024 * 1024);
const UPSTREAM_TIMEOUT_MS = Number(process.env.UPSTREAM_TIMEOUT_MS || 6000);

const JSON_HEADERS = {
  'Content-Type': 'application/json',
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

function sendJson(res, status, body) {
  if (res.headersSent) {
    return;
  }
  const payload = JSON.stringify(body);
  res.writeHead(status, { ...JSON_HEADERS, 'Content-Length': Buffer.byteLength(payload) });
  res.end(payload);
}

/** 发送单行命令（如 zPING）并等待 clamd 以 NUL/换行结束的响应。 */
function clamdCommand(command, timeoutMs) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: CLAMD_HOST, port: CLAMD_PORT });
    let data = '';
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('timeout'));
    }, timeoutMs);
    const settle = (fn, value) => {
      clearTimeout(timer);
      socket.destroy();
      fn(value);
    };
    socket.on('connect', () => socket.write(command));
    socket.on('data', (chunk) => {
      data += chunk.toString('utf8');
      if (/[\0\n]/.test(data)) {
        settle(resolve, data.replace(/[\0\n]+$/g, '').trim());
      }
    });
    socket.on('error', (error) => settle(reject, error));
    socket.on('close', () => {
      if (!data) {
        settle(reject, new Error('closed'));
      }
    });
  });
}

/** 将文件流按 INSTREAM 协议发送给 clamd，返回是否 clean。 */
function scanStream(fileStream) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: CLAMD_HOST, port: CLAMD_PORT });
    let response = '';
    let settled = false;

    // 立即排队写入命令：文件块在连接建立前就可能到达，
    // 若把命令放到 connect 回调里，会导致长度帧先于命令发送、clamd 返回 UNKNOWN COMMAND。
    socket.write('zINSTREAM\0');

    const finish = (error, value) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      fileStream.destroy();
      if (error) {
        reject(error);
      } else {
        resolve(value);
      }
    };

    const timer = setTimeout(() => finish(new Error('timeout')), UPSTREAM_TIMEOUT_MS);

    socket.on('data', (chunk) => {
      response += chunk.toString('utf8');
      if (/[\0\n]/.test(response)) {
        const line = response.replace(/[\0\n]+$/g, '').trim();
        if (/OK$/.test(line)) {
          finish(null, true);
        } else if (/FOUND$/.test(line)) {
          finish(null, false);
        } else {
          finish(new Error('unexpected_response'));
        }
      }
    });
    socket.on('error', (error) => finish(error));
    socket.on('close', () => {
      if (!settled && !response) {
        finish(new Error('closed'));
      }
    });

    fileStream.on('data', (chunk) => {
      if (settled) {
        return;
      }
      const header = Buffer.alloc(4);
      header.writeUInt32BE(chunk.length, 0);
      const ok = socket.write(Buffer.concat([header, chunk]));
      if (!ok) {
        fileStream.pause();
        socket.once('drain', () => fileStream.resume());
      }
    });
    fileStream.on('end', () => {
      if (!settled) {
        socket.write(Buffer.from([0, 0, 0, 0]));
      }
    });
    fileStream.on('error', (error) => finish(error));
  });
}

function handleScan(req, res) {
  if (SCAN_TOKEN && req.headers['x-scan-token'] !== SCAN_TOKEN) {
    sendJson(res, 401, { error: 'unauthorized' });
    return;
  }

  let busboy;
  try {
    busboy = Busboy({
      headers: req.headers,
      limits: { files: 1, fileSize: MAX_FILE_BYTES },
    });
  } catch {
    sendJson(res, 400, { error: 'bad_request' });
    return;
  }

  let handled = false;
  let responded = false;
  const respond = (status, body) => {
    if (!responded) {
      responded = true;
      sendJson(res, status, body);
    }
  };

  busboy.on('file', (_name, stream) => {
    if (handled) {
      stream.resume();
      return;
    }
    handled = true;
    stream.on('limit', () => {
      respond(413, { error: 'file_too_large' });
    });
    scanStream(stream)
      .then((clean) => respond(200, { clean }))
      .catch(() => respond(503, { error: 'scan_unavailable' }));
  });
  busboy.on('filesLimit', () => respond(400, { error: 'too_many_files' }));
  busboy.on('error', () => respond(400, { error: 'bad_request' }));
  busboy.on('finish', () => {
    if (!handled) {
      respond(400, { error: 'missing_file' });
    }
  });

  req.pipe(busboy);
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    clamdCommand('zPING\0', 2000)
      .then((reply) => {
        if (reply.startsWith('PONG')) {
          sendJson(res, 200, { status: 'UP' });
        } else {
          sendJson(res, 503, { status: 'DOWN' });
        }
      })
      .catch(() => sendJson(res, 503, { status: 'DOWN' }));
    return;
  }
  if (req.method === 'POST' && req.url === '/scan') {
    handleScan(req, res);
    return;
  }
  sendJson(res, 404, { error: 'not_found' });
});

server.headersTimeout = 15000;
server.requestTimeout = 30000;
server.keepAliveTimeout = 10000;

server.listen(PORT, '0.0.0.0', () => {
  // 仅输出监听端口，不输出扫描地址与令牌
  console.log(`[scan-adapter] listening on :${PORT}`);
});
