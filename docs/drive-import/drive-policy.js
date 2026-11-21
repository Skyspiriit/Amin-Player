(function (root) {
  'use strict';
  const MAX_BYTES = 1048576;
  class DriveImportError extends Error {
    constructor(code) { super(code); this.code = code; }
  }
  function localOrigin(origin) {
    try {
      const u = new URL(origin);
      if (u.protocol !== 'https:' || u.origin !== origin || !u.port || u.username || u.password) return false;
      const a = u.hostname.split('.').map(Number);
      if (a.length !== 4 || a.some((n, i) => !Number.isInteger(n) || n < 0 || n > 255 || String(n) !== u.hostname.split('.')[i])) return false;
      return a[0] === 10 || (a[0] === 172 && a[1] >= 16 && a[1] <= 31) ||
        (a[0] === 192 && a[1] === 168) || u.hostname === '127.0.0.1';
    } catch { return false; }
  }
  function fileId(value) {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(value)) throw new DriveImportError('UNAVAILABLE');
    return value;
  }
  function metadata(value) {
    const size = Number(value.size);
    if (!Number.isSafeInteger(size) || size < 1 || size > MAX_BYTES) throw new DriveImportError('SIZE');
    if (value.trashed || value.capabilities?.canDownload === false) throw new DriveImportError('UNAVAILABLE');
    if (typeof value.mimeType !== 'string' || value.mimeType.startsWith('application/vnd.google-apps.')) throw new DriveImportError('FORMAT');
    // Neither a filename suffix nor a folder name grants or denies an import.
    return {name: String(value.name || 'فایل انتخاب‌شده').slice(0, 256), size};
  }
  async function download(token, id, request, signal) {
    const base = 'https://www.googleapis.com/drive/v3/files/' + fileId(id);
    const options = {headers: {Authorization: 'Bearer ' + token}, credentials: 'omit', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer', signal};
    async function get(url) {
      let response;
      try { response = await request(url, options); } catch { throw new DriveImportError('NETWORK'); }
      if (response.status === 401) throw new DriveImportError('EXPIRED');
      if (response.status === 403) throw new DriveImportError('DENIED');
      if (response.status === 404 || response.status === 410) throw new DriveImportError('UNAVAILABLE');
      if (!response.ok) throw new DriveImportError('NETWORK');
      return response;
    }
    const response = await get(base + '?supportsAllDrives=true&fields=name,size,mimeType,trashed,capabilities(canDownload)');
    let info;
    try { info = metadata(await response.json()); } catch (error) { throw error instanceof DriveImportError ? error : new DriveImportError('FORMAT'); }
    const media = await get(base + '?alt=media&supportsAllDrives=true');
    const length = Number(media.headers.get('Content-Length'));
    if (Number.isFinite(length) && length > MAX_BYTES) { await media.body?.cancel(); throw new DriveImportError('SIZE'); }
    const chunks = []; let size = 0;
    try {
      if (!media.body || typeof media.body.getReader !== 'function') throw new DriveImportError('BROWSER');
      const reader = media.body.getReader();
      try {
        while (true) {
          const next = await reader.read(); if (next.done) break;
          size += next.value.byteLength;
          if (size > MAX_BYTES) { next.value.fill(0); await reader.cancel(); throw new DriveImportError('SIZE'); }
          chunks.push(next.value);
        }
      } finally { reader.releaseLock(); }
      if (!size) throw new DriveImportError('SIZE');
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      return {name: info.name, bytes};
    } catch (error) { throw error instanceof DriveImportError ? error : new DriveImportError('NETWORK'); }
    finally { chunks.forEach(chunk => chunk.fill(0)); }
  }
  const api = {MAX_BYTES, DriveImportError, localOrigin, fileId, metadata, download};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.AminDrivePolicy = api;
})(typeof window === 'undefined' ? globalThis : window);
