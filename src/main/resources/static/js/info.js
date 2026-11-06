/**
 * Информационная страница о подписании документа.
 * Fetch /api/info/{uuid} и рендер.
 */
(function () {
    'use strict';

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function fmtDate(iso) {
        if (!iso) return '—';
        try {
            var d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            var pad = function (n) { return String(n).padStart(2, '0'); };
            return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + '.' + d.getFullYear()
                + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
        } catch (e) { return iso; }
    }

    function fmtSize(bytes) {
        if (!bytes) return '—';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1024 / 1024).toFixed(2) + ' MB';
    }

    async function init(uuid) {
        var loading = document.getElementById('info-loading');
        var output = document.getElementById('info-output');
        var timeEl = document.getElementById('info-time');

        if (timeEl) timeEl.textContent = new Date().toLocaleString('ru-RU');

        if (!uuid) {
            loading.style.display = 'none';
            output.style.display = 'block';
            output.innerHTML = '<div class="info-alert err">UUID документа не указан.</div>';
            return;
        }

        try {
            var resp = await fetch('/api/info/' + encodeURIComponent(uuid));
            loading.style.display = 'none';
            output.style.display = 'block';

            if (resp.status === 404) {
                output.innerHTML = '<div class="info-alert err">'
                    + 'Документ не найден или ссылка устарела.</div>';
                return;
            }
            if (!resp.ok) {
                output.innerHTML = '<div class="info-alert err">'
                    + 'Ошибка ' + resp.status + '</div>';
                return;
            }

            var data = await resp.json();
            render(output, data);
        } catch (e) {
            loading.style.display = 'none';
            output.style.display = 'block';
            output.innerHTML = '<div class="info-alert err">Ошибка: ' + esc(e.message) + '</div>';
        }
    }

    function render(container, data) {
        var html = '';

        // ─── Документ ───
        html += '<div class="info-card">';
        html += '<h2 class="info-card-title">Документ</h2>';
        html += '<dl class="info-dl">';
        html += '<dt>Название</dt><dd>' + esc(data.documentName || '—') + '</dd>';
        html += '<dt>Идентификатор</dt><dd><code>#' + esc(String(data.documentId)) + '</code></dd>';
        html += '<dt>Размер</dt><dd>' + fmtSize(data.sizeBytes) + '</dd>';
        html += '<dt>Загружен</dt><dd>' + fmtDate(data.uploadedAt) + '</dd>';
        if (data.sha256) {
            html += '<dt>SHA-256</dt><dd><code>' + esc(data.sha256) + '</code></dd>';
        }
        html += '</dl></div>';

        // ─── Подписи ───
        var sigs = data.signatures || [];
        html += '<div class="info-card">';
        html += '<h2 class="info-card-title">Подписи (' + sigs.length + ')</h2>';

        if (sigs.length === 0) {
            html += '<p class="info-muted">На момент проверки документ не подписан.</p>';
        } else {
            sigs.forEach(function (s, i) {
                var statusCls = s.cryptoOk ? 'ok' : 'err';
                var statusIcon = s.cryptoOk ? '✓' : '✗';
                var statusText = s.cryptoOk ? 'Криптоподпись корректна' : 'Криптоподпись не прошла';

                html += '<div class="info-sig">';
                html += '<div class="info-sig-head">';
                html += '<div class="info-sig-num">' + (i + 1) + '</div>';
                html += '<div class="info-sig-subject">' + esc(s.subject || 'Подписант') + '</div>';
                html += '</div>';
                html += '<dl class="info-dl">';
                html += '<dt>Дата</dt><dd>' + fmtDate(s.signedAt) + '</dd>';
                if (s.serial) {
                    html += '<dt>Серийный номер</dt><dd><code>' + esc(s.serial) + '</code></dd>';
                }
                html += '<dt>Статус</dt>';
                html += '<dd class="info-status ' + statusCls + '">'
                    + statusIcon + ' ' + esc(statusText);
                if (!s.cryptoOk && s.cryptoMessage) {
                    html += ' <span class="info-muted">(' + esc(s.cryptoMessage) + ')</span>';
                }
                html += '</dd>';
                html += '</dl>';
                html += '</div>';
            });
        }
        html += '</div>';

        container.innerHTML = html;
    }

    window.InfoUI = { init: init };
})();
