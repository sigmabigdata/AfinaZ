/**
 * Массовое подписание документов.
 *
 * Работает на /dashboard и /admin/users/{id}.
 *
 * Логика:
 *   1. Пользователь ставит чекбоксы на документах.
 *   2. Появляется плавающая панель «Подписать N выбранных».
 *   3. По клику — модалка прогресса.
 *   4. Для каждого документа скачивается файл и подписывается плагином (один PIN на всё).
 *   5. Список {documentId, signatureBase64} отправляется на /api/sign/accept-batch.
 */
(function () {
    'use strict';

    var API_PREFIX = document.body.dataset.apiPrefix || '';
    var selected = new Set();
    var isAdmin = (API_PREFIX === '/admin');

    function init() {
        injectBatchBar();
        // Делегируем обработку чекбоксов
        document.addEventListener('change', function (e) {
            if (!e.target.classList.contains('doc-checkbox')) return;
            var id = Number(e.target.dataset.id);
            if (e.target.checked) {
                selected.add(id);
            } else {
                selected.delete(id);
            }
            updateBar();
        });
    }

    function injectBatchBar() {
        var bar = document.createElement('div');
        bar.className = 'batch-bar';
        bar.id = 'batch-bar';
        bar.innerHTML = ''
            + '<span>Выбрано: <span class="count" id="batch-count">0</span></span>'
            + '<button id="batch-sign-btn">✍ Подписать выбранные</button>'
            + '<button class="ghost" id="batch-clear-btn">Сбросить</button>';
        document.body.appendChild(bar);

        document.getElementById('batch-sign-btn').addEventListener('click', signSelected);
        document.getElementById('batch-clear-btn').addEventListener('click', clearSelection);
    }

    function updateBar() {
        var count = selected.size;
        document.getElementById('batch-count').textContent = count;
        var bar = document.getElementById('batch-bar');
        if (count > 0) {
            bar.classList.add('visible');
        } else {
            bar.classList.remove('visible');
        }
    }

    function clearSelection() {
        selected.clear();
        document.querySelectorAll('.doc-checkbox').forEach(function (cb) {
            cb.checked = false;
        });
        updateBar();
    }

    async function fetchFileBase64(id) {
        var resp = await fetch(API_PREFIX + '/documents/' + id + '/view', { credentials: 'same-origin' });
        if (!resp.ok) throw new Error('Файл ' + id + ' не получен: HTTP ' + resp.status);
        var buf = await resp.arrayBuffer();
        return arrayBufferToBase64(buf);
    }

    async function signSelected() {
        if (selected.size === 0) return;

        var progress = window.SignProgress;
        var total = selected.size;

        // Кастомные шаги для batch
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
            var cert = await CertSelector.choose(certs);
            if (!cert) {
                if (progress) progress.fail('Подписание отменено');
                return;
            }
            if (progress) progress.done(0);

            // Шаг 1: подписываем каждый файл
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

            // Шаг 2: отправка на сервер
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
                setTimeout(function () { location.reload(); }, 1500);
            } else {
                if (progress) progress.fail('Сервер вернул ошибку: ' + (data.error || resp.statusText));
            }
        } catch (e) {
            console.error('[batch-sign] error', e);
            if (progress) progress.fail('Ошибка: ' + (e && e.message ? e.message : e));
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

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
