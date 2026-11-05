import cadesplugin from './vendor/crypto-pro-cadesplugin.js';

console.log('[sign.js] module loaded');

document.addEventListener('DOMContentLoaded', function () {
    // Префикс API: "/admin" — если страница админская, "" — если клиентская
    var API_PREFIX = document.body.dataset.apiPrefix || '';

    var toast = document.createElement('div');
    toast.id = 'status-toast';
    document.body.appendChild(toast);

    function show(msg, kind) {
        console.log('[sign.js]', kind || 'info', msg);
        toast.textContent = msg;
        toast.className = kind || '';
        toast.style.display = 'block';
    }

    document.addEventListener('click', function (ev) {
        var btn = ev.target.closest('.btn-sign');
        if (!btn) return;
        ev.preventDefault();
        var id = btn.getAttribute('data-id');
        if (id) signDocument(id);
    });

    async function signDocument(id) {
        var progress = window.SignProgress;
        if (progress) progress.open({ title: 'Подписание документа' });

        try {
            // Шаг 0: поиск сертификата
            if (progress) progress.active(0);
            var respFile = await fetch(API_PREFIX + '/documents/' + id + '/view', { credentials: 'same-origin' });
            if (!respFile.ok) throw new Error('Файл не получен: HTTP ' + respFile.status);
            var buf = await respFile.arrayBuffer();
            var contentBase64 = arrayBufferToBase64(buf);
            console.log('[sign.js] file loaded, bytes =', buf.byteLength);

            var api = await cadesplugin();
            var certs = await api.getCertsList();
            if (!certs || certs.length === 0) throw new Error('В хранилище «Личные» нет сертификатов');
            var cert = certs[0];
            if (progress) progress.done(0);

            // Шаг 1: PIN (плагин КриптоПро)
            if (progress) progress.active(1);
            // сам ввод PIN происходит внутри api.signBase64(), поэтому
            // отмечаем шаг 1 done сразу перед вызовом, а шаг 2 — active.
            if (progress) progress.done(1);

            // Шаг 2: подписание
            if (progress) progress.active(2);
            var signatureRaw = await api.signBase64(cert.thumbprint, contentBase64);
            var signatureB64 = sanitizeBase64(signatureRaw);
            console.log('[sign.js] signature length =', signatureB64.length);
            if (progress) progress.done(2);

            // Шаг 3: проверка на сервере
            if (progress) progress.active(3);
            var headers = { 'Content-Type': 'application/json' };
            var csrf = getCsrf();
            if (csrf) headers[getCsrfHeader() || 'X-CSRF-TOKEN'] = csrf;

            var endpoint = (API_PREFIX === '/admin') ? '/api/sign/admin/accept' : '/api/sign/accept';

            var resp = await fetch(endpoint, {
                method: 'POST',
                credentials: 'same-origin',
                headers: headers,
                body: JSON.stringify({ documentId: id, signatureBase64: signatureB64 })
            });
            var data = await resp.json().catch(function () { return {}; });
            if (resp.ok && data.valid) {
                if (progress) progress.success('Документ подписан');
                setTimeout(function () { location.reload(); }, 1300);
            } else {
                var msg = 'Подпись не принята: ' + (data.error || resp.statusText);
                if (progress) {
                    progress.error(3, data.error || resp.statusText);
                    progress.fail(msg);
                } else {
                    show(msg, 'err');
                }
            }
        } catch (e) {
            console.error('[sign.js] error', e);
            var errMsg = 'Ошибка: ' + (e && e.message ? e.message : e);
            if (progress) {
                // Найти активный шаг и пометить его ошибкой
                for (var i = 0; i < 4; i++) {
                    var stepEl = document.querySelector('.sign-step[data-step="' + i + '"]');
                    if (stepEl && stepEl.classList.contains('active')) {
                        progress.error(i, e.message || String(e));
                        break;
                    }
                }
                progress.fail(errMsg);
            } else {
                show(errMsg, 'err');
            }
        }
    }

    function sanitizeBase64(s) {
        if (!s) return s;
        return String(s)
            .replace(/-----BEGIN[^-]*-----/g, '')
            .replace(/-----END[^-]*-----/g, '')
            .replace(/[^A-Za-z0-9+/=]/g, '');
    }

    function arrayBufferToBase64(buf) {
        var binary = '', bytes = new Uint8Array(buf), chunk = 0x8000;
        for (var i = 0; i < bytes.length; i += chunk)
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        return btoa(binary);
    }
    function getCsrf() { var m = document.querySelector('meta[name="_csrf"]'); return m ? m.getAttribute('content') : null; }
    function getCsrfHeader() { var m = document.querySelector('meta[name="_csrf_header"]'); return m ? m.getAttribute('content') : null; }
});
