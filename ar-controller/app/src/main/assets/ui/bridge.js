// Thin promise wrapper around window.ARNative (Bridge.kt). Falls back to mock data in a desktop browser.
(function () {
  var N = window.ARNative;
  var seq = 0, pending = {}, listeners = {};

  window.__arcb = function (id, payload) {
    var p = pending[id];
    if (p) { delete pending[id]; p(payload); }
  };
  window.AR_event = function (type, data) {
    (listeners[type] || []).forEach(function (f) { try { f(data || {}); } catch (e) { console.error(e); } });
  };
  function cb(fn) {
    var id = 'c' + (++seq);
    return new Promise(function (resolve) { pending[id] = resolve; fn(id); });
  }

  var MOCK = {
    status: { overlay: true, accessibility: false, microphone: true, contacts: true, phone: false, notifications: true,
      writeSettings: false, dnd: false, freeform: false, devOptions: false, serviceRunning: false, sdk: 30, battery: 64,
      version: '2.0', lastError: null },
    config: { hasApiKey: true, baseUrl: 'https://api.groq.com/openai/v1', model: 'openai/gpt-oss-120b', language: 'en-IN',
      wakeWord: true, freeAi: true, bottomOrb: true },
    apps: ['YouTube', 'WhatsApp', 'Chrome', 'Instagram', 'Facebook', 'TikTok', 'Gmail', 'Maps', 'Camera', 'Calculator', 'Settings', 'Spotify']
      .map(function (l) { return { label: l, pkg: 'com.example.' + l.toLowerCase(), icon: '' }; })
  };

  window.AR = {
    mock: !N,
    on: function (t, f) { (listeners[t] = listeners[t] || []).push(f); },
    status: function () { return N ? JSON.parse(N.status()) : MOCK.status; },
    config: function () { return N ? JSON.parse(N.config()) : MOCK.config; },
    saveConfig: function (o) { return N ? N.saveConfig(JSON.stringify(o)) : 'ok'; },
    request: function (w) { if (N) N.request(w); },
    start: function () { return N ? N.startController() : 'ok'; },
    stop: function () { if (N) N.stopController(); },
    run: function (text) {
      return N ? cb(function (id) { N.run(text, id); }) : Promise.resolve({ action: 'chat', reply: 'Preview: ' + text });
    },
    action: function (name, arg) {
      return N ? cb(function (id) { N.action(name, arg || '', id); }) : Promise.resolve('ok');
    },
    apps: function () { return N ? cb(function (id) { N.apps(id); }) : Promise.resolve(MOCK.apps); },
    launch: function (pkg, mode) {
      return N ? cb(function (id) { N.launch(pkg, mode, id); }) : Promise.resolve('');
    },
    listen: function () { if (N) N.listen(); },
    host: function (cmd, arg) { return N ? N.host(cmd, arg || '') : ''; },
    copy: function (t) { if (N) N.copy(t); },
    clearError: function () { if (N) N.clearError(); },
    openUrl: function (u) { if (N) N.openUrl(u); else window.open(u); }
  };

  window.$ = function (s, root) { return (root || document).querySelector(s); };
  window.$$ = function (s, root) { return Array.prototype.slice.call((root || document).querySelectorAll(s)); };
  window.esc = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };
  window.toast = function (msg) {
    var t = $('.toast');
    if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t._h);
    t._h = setTimeout(function () { t.classList.remove('show'); }, 2600);
  };
  // Replace <i data-icon="name"> placeholders with inline SVG.
  window.paintIcons = function (root) {
    $$('[data-icon]', root).forEach(function (el) {
      el.outerHTML = icon(el.getAttribute('data-icon'), el.getAttribute('data-size') || '');
    });
  };
})();
