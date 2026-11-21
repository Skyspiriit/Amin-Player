(function () {
  'use strict';
  const policy = window.AminDrivePolicy, config = window.AminGoogle;
  const choose = document.getElementById('choose'), cancel = document.getElementById('cancel'), status = document.getElementById('status');
  const SCOPE = 'https://www.googleapis.com/auth/drive.file';
  let session = null, accessToken = null, tokenDeadline = 0, tokenClient = null, picker = null, controller = null, readyTimer = null, expiry = null;
  let busy = false, initialized = false;
  function notify(type, values) {
    if (session && window.opener) window.opener.postMessage(Object.assign({type, session: session.nonce}, values), session.origin);
  }
  function resetToken() { accessToken = null; tokenDeadline = 0; }
  function clear() {
    controller?.abort(); controller = null; resetToken();
    picker?.dispose(); picker = null;
    clearInterval(readyTimer); clearTimeout(expiry);
  }
  function message(code) {
    return {
      EXPIRED: 'اجازهٔ موقت Google تمام شده است. دوباره «انتخاب فایل» را بزنید.',
      DENIED: 'Google اجازهٔ دریافت این فایل را نداد. فایل را دوباره انتخاب و اجازهٔ لازم را تأیید کنید.',
      UNAVAILABLE: 'این فایل حذف شده یا در دسترس نیست. فایل دیگری انتخاب کنید.',
      SIZE: 'فایل باید غیرخالی و حداکثر ۱ مگابایت باشد. فایل تنظیمات متنی را انتخاب کنید.',
      FORMAT: 'یک فایل متنی تنظیمات انتخاب کنید. پوشه یا سند آنلاین Google قابل دریافت نیست.',
      BROWSER: 'این مرورگر دریافت مستقیم را پشتیبانی نمی‌کند. به صفحهٔ قبل برگردید و فایل را از تلفن انتخاب کنید.',
      NETWORK: 'دریافت از Google انجام نشد. اینترنت تلفن را بررسی و دوباره انتخاب کنید.'
    }[code] || 'اجازهٔ Google دریافت نشد یا انتخاب لغو شد. دوباره تلاش کنید.';
  }
  function fail(code) {
    if (code === 'EXPIRED' || code === 'DENIED') resetToken();
    busy = false; choose.disabled = !initialized || !session; status.textContent = message(code);
  }
  function loadScript(url) {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script'); script.src = url; script.async = true;
      script.onload = resolve; script.onerror = () => reject(new Error('NETWORK')); document.head.appendChild(script);
    });
  }
  async function receiveFile(id) {
    if (!session || Date.now() >= session.deadline) return expired();
    if (!accessToken || Date.now() >= tokenDeadline) return fail('EXPIRED');
    busy = true; choose.disabled = true; status.textContent = 'در حال دریافت خصوصی فایل از Google…';
    controller = new AbortController(); const current = session;
    const timer = setTimeout(() => controller?.abort(), 120000);
    let selected;
    try {
      selected = await policy.download(accessToken, id, fetch.bind(window), controller.signal);
      if (session !== current || !window.opener || window.opener.closed || Date.now() >= current.deadline) return expired();
      resetToken();
      window.opener.postMessage({type: 'amin-drive-file', session: current.nonce, name: selected.name, bytes: selected.bytes.buffer}, current.origin, [selected.bytes.buffer]);
      status.textContent = 'فایل به صفحهٔ قبلی تحویل داده شد. در آن صفحه «ارسال به تلویزیون» و سپس روی تلویزیون «ذخیره» را بزنید.';
      clear(); session = null; choose.disabled = true;
      // The local TV page closes this window after validating its source and session.
    } catch (error) { fail(error?.code); }
    finally { clearTimeout(timer); if (selected?.bytes.byteLength) selected.bytes.fill(0); busy = false; }
  }
  function pickerResult(data) {
    if (!session || Date.now() >= session.deadline) return expired();
    if (data.action === google.picker.Action.CANCEL) {
      resetToken(); busy = false; choose.disabled = false;
      status.textContent = 'انتخاب فایل لغو شد. تنظیمات تلویزیون تغییری نکرد. می‌توانید دوباره انتخاب کنید.';
      picker?.dispose(); picker = null;
    } else if (data.action === google.picker.Action.PICKED && data.docs?.length === 1) {
      picker?.dispose(); picker = null;
      receiveFile(data.docs[0].id);
    }
  }
  function showPicker() {
    if (!session || Date.now() >= session.deadline) return expired();
    const view = new google.picker.DocsView(google.picker.ViewId.DOCS)
      .setIncludeFolders(true).setSelectFolderEnabled(false).setEnableDrives(true);
    picker?.dispose();
    picker = new google.picker.PickerBuilder().addView(view).setLocale('fa')
      .setOAuthToken(accessToken).setDeveloperKey(config.pickerKey).setAppId(config.projectNumber)
      .setOrigin(location.origin).setTitle('فایل تنظیمات را از هر پوشه‌ای انتخاب کنید')
      .setCallback(pickerResult).setSize(Math.max(280, Math.min(innerWidth - 12, 900)), Math.max(300, Math.min(innerHeight - 12, 700)))
      .build();
    picker.setVisible(true); status.textContent = 'فایل را از هر پوشه‌ای انتخاب کنید. نام فایل و پوشه مهم نیست.';
  }
  function expired() {
    clear(); session = null; busy = false; choose.disabled = true;
    status.textContent = 'زمان دریافت تلویزیون تمام شد یا صفحهٔ قبلی بسته شد. دریافت از تلفن را روی تلویزیون دوباره باز و QR جدید را اسکن کنید.';
  }
  choose.addEventListener('click', () => {
    if (!initialized || !session || busy) return;
    if (Date.now() >= session.deadline) return expired();
    if (accessToken && Date.now() < tokenDeadline) { showPicker(); return; }
    busy = true; choose.disabled = true; status.textContent = 'در پنجرهٔ Google، حساب خودتان را انتخاب و اجازهٔ فایل انتخابی را تأیید کنید.';
    tokenClient.requestAccessToken({prompt: 'select_account'});
  });
  cancel.addEventListener('click', () => { notify('amin-drive-cancel'); clear(); session = null; choose.disabled = true; window.close(); });
  window.addEventListener('message', event => {
    if (session || !window.opener || event.source !== window.opener || !policy.localOrigin(event.origin) || event.data?.type !== 'amin-drive-init') return;
    if (!/^[a-f0-9]{32}$/.test(event.data.session) || !Number.isInteger(event.data.expiresIn) || event.data.expiresIn < 1 || event.data.expiresIn > 600) return;
    session = {nonce: event.data.session, origin: event.origin, deadline: Date.now() + event.data.expiresIn * 1000};
    clearInterval(readyTimer); expiry = setTimeout(expired, event.data.expiresIn * 1000);
    choose.disabled = !initialized; status.textContent = initialized ? 'آماده است. برای انتخاب فایل، دکمهٔ بالا را بزنید.' : 'در حال آماده‌سازی Google…';
  });
  window.addEventListener('pagehide', () => { clear(); session = null; });
  if (!window.opener) { status.textContent = 'این صفحه را از کد QR تلویزیون باز کنید. ابتدا وارد صفحهٔ خصوصی تلویزیون شوید و «انتخاب از Google Drive» را بزنید.'; return; }
  if (!config || !/^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/.test(config.clientId) || !config.pickerKey) {
    status.textContent = 'انتخاب مستقیم Google فعلاً آماده نیست. به صفحهٔ قبل برگردید و فایل را از تلفن انتخاب کنید.'; return;
  }
  readyTimer = setInterval(() => window.opener?.postMessage({type: 'amin-drive-ready'}, '*'), 500);
  window.opener.postMessage({type: 'amin-drive-ready'}, '*'); // No token, file, pairing code or address is in this greeting.
  Promise.all([loadScript('https://apis.google.com/js/api.js'), loadScript('https://accounts.google.com/gsi/client')])
    .then(() => new Promise((resolve, reject) => gapi.load('picker', {callback: resolve, onerror: reject, timeout: 20000, ontimeout: reject})))
    .then(() => {
      tokenClient = google.accounts.oauth2.initTokenClient({client_id: config.clientId, scope: SCOPE, include_granted_scopes: false,
        callback: response => {
          busy = false;
          if (response.error || !response.access_token || !google.accounts.oauth2.hasGrantedAllScopes(response, SCOPE)) { fail('DENIED'); return; }
          accessToken = response.access_token; tokenDeadline = Date.now() + Math.max(0, Number(response.expires_in || 0) - 30) * 1000;
          choose.disabled = !session;
          try { showPicker(); } catch { fail('NETWORK'); }
        }, error_callback: () => { busy = false; resetToken(); choose.disabled = !session; status.textContent = 'ورود به Google لغو شد یا پنجره باز نشد. دوباره دکمهٔ انتخاب را بزنید.'; }
      });
      initialized = true; choose.disabled = !session;
      status.textContent = session ? 'آماده است. برای انتخاب فایل، دکمهٔ بالا را بزنید.' : 'در انتظار صفحهٔ خصوصی تلویزیون…';
    }).catch(() => { clear(); status.textContent = 'Google بارگذاری نشد. اینترنت تلفن را بررسی کنید؛ می‌توانید به صفحهٔ قبل برگردید و فایل را از تلفن انتخاب کنید.'; });
})();
