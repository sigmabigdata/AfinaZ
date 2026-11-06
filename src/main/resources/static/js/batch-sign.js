/**
 * Массовое подписание документов.
 * Работает на /dashboard (клиент) и /admin/users/{id} (админ).
 *
 * Точка входа: window.BatchSign.signAllInSection(folderId)
 * Вызывается из inline onclick в шаблоне.
 */
(function () {
    'use strict';

    var API_PREFIX = document.body.dataset.apiPrefix || '';
    var isAdmin = (API_PREFIX === '/admin');
    var selected = new Set();

    // ═══════════════════════════════════════════════════════
    //  Подписание всех неподписанных в разделе
    // ═══════════════════════════════════════════════════════
    function signAllInSection(folderId) {
        var card = document.querySelector('.folder-card[data-folder-id="' + folderId + '"]');
        if (!card) {
            alert('Раздел не найден на странице');
            return;
        }
        var ids = [];
        card.querySelectorAll('.doc-card[data-signed="false"], tr[data-signed="false"]').forEach(function (el) {
            var id = Number(el.dataset.id || el.dataset.docId);
            if (id) ids.push(id);
        });

        if (ids.length === 0) {
            alert('В этом разделе все документы уже подписаны');
            return;
        }

        var n = ids.length;
        if (!confirm('Подписать ' + n + ' неподписанных документа(ов) в разделе?\n'
                + 'Потребуется один PIN-код для всех.')) {
            return;
        }

        console.log('[signAllInSection] folderId=', folderId, 'ids=', ids);

        selected.clear();
        ids.forEach(function (id) { selected.add(id); });
        signSelected();
    }

    // ═══════════════════════════════════════════════════════
    //  Подписание всех неподписанных на странице
    // ═══════════════════════════════════════════════════════
    function signAllUnsigned() {
        var ids = [];
        document.querySelectorAll('.doc-card[data-signed="false"], tr[data-signed="false"]').forEach(function (el) {
            var id = Number(el.dataset.id || el.dataset.docId);
            if (id) ids.push(id);
        });
        if (ids.length === 0) {
            alert('Нет неподписанных документов');
            return;
        }
        if (!confirm('Подписать все ' + ids.length + ' неподписанных документа(ов)?\n'
                + 'Потребуется один PIN-код для всех.')) {
            return;
        }
        selected.clear();
        ids.forEach(function (id) { selected.add(id); });
        signSelected();
    }

    // ═══════════════════════════════════════════════════════
    //  Основной процесс подписания
    // ═══════════════════════════════════════════════════════
    async function signSelected() {
        if (selected.size === 0) {
            alert('Ничего не выбрано');
            return;
        }

        var progress = window.SignProgress;
        var total = selected.size;

        var steps = [
            { icon: '🔍', title: 'Поиск сертификата', sub: 'Чтение из хранилища «Личные»' },
            { icon: '🖋', title: 'Подписание ' + total + ' документ(ов)', sub: 'Один PIN на все файлы' },
            { icon: '✓', title: 'Проверка на сервере', sub: 'Криптопроверка и запись подписей' }
        ];
        if (progress) {
            progress.open({
                title: 'Массовое подписание (' + total + ')',
                subtitle: 'Не закрывайте окно — процесс идёт',
                steps: steps
            });
        }

        try {
            // Шаг 0: сертификат
            if (progress) progress.active(0);
            var cadesplugin = (await import('/js/vendor/crypto-pro-cadesplugin.js')).default;
            var api = await cadesplugin();
            var certs = await api.getCertsList();
            if (!certs || certs.length === 0) throw new Error('В хранилище «Личные» нет сертификатов');

            // CertSelector — если определён
            var cert;
            if (window.CertSelector && typeof window.CertSelector.choose === 'function') {
                cert = await window.CertSelector.choose(certs);
                if (!cert) {
                    if (progress) progress.fail('Подписание отменено');
                    return;
                }
            } else {
                // fallback: первый
                cert = certs[0];
            }
            if (progress) progress.done(0);

            // Шаг 1: подписываем каждый
            if (progress) progress.active(1);
            var ids = Array.from(selected);
            var requests = [];
            var sub = document.querySelector('.sign-step[data-step="1"] .sign-step-sub');

            for (var i = 0; i < ids.length; i++) {
                var id = ids[i];
                if (sub) sub.textContent = 'Файл ' + (i + 1) + ' из ' + total + '…';
                var contentBase64 = await fetchFileBase64(id);
                var signatureRaw = await api.signBase64(cert.thumbprint, contentBase64);
                requests.push({
                    documentId: id,
                    signatureBase64: sanitizeBase64(signatureRaw)
                });
            }
            if (progress) progress.done(1);

            // Шаг 2: отправка
            if (progress) progress.active(2);
            var endpoint = isAdmin ? '/api/sign/admin/accept-batch' : '/api/sign/accept-batch';
            var resp = await fetch(endpoint, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requests)
            });
            var data = await resp.json().catch(function () { return {}; });
            if (progress) progress.done(2);

            if (resp.ok) {
                var msg = 'Подписано ' + data.ok + ' из ' + data.total;
                if (data.fail > 0) msg += ' (ошибок: ' + data.fail + ')';
                if (progress) progress.success(msg);
                selected.clear();
                setTimeout(function () {
                    if (window.StampEffect) {
                        StampEffect.play('ПОДПИСАНО', hardReload);
                    } else {
                        hardReload();
                    }
                }, 1400);
            } else {
                if (progress) progress.fail('Сервер вернул ошибку: ' + (data.error || resp.statusText));
            }
        } catch (e) {
            console.error('[batch-sign] error', e);
            if (progress) progress.fail('Ошибка: ' + (e && e.message ? e.message : e));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Хелперы
    // ═══════════════════════════════════════════════════════
    async function fetchFileBase64(id) {
        var resp = await fetch(API_PREFIX + '/documents/' + id + '/view', { credentials: 'same-origin' });
        if (!resp.ok) throw new Error('Файл ' + id + ' не получен: HTTP ' + resp.status);
        var buf = await resp.arrayBuffer();
        return arrayBufferToBase64(buf);
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

    function hardReload() {
        var url = new URL(location.href);
        url.searchParams.set('_t', Date.now());
        location.href = url.toString();
    }

    // ═══════════════════════════════════════════════════════
    //  Экспорт
    // ═══════════════════════════════════════════════════════
    window.BatchSign = {
        signAllUnsigned: signAllUnsigned,
        signAllInSection: signAllInSection
    };

    console.log('[batch-sign] loaded. API_PREFIX=', API_PREFIX, '| isAdmin=', isAdmin);
})();
