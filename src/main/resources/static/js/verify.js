/**
 * Публичная верификация подписи.
 * Работает на страницах /verify (проверка .sig) и /verify/{uuid} (инфо о документе).
 */
(function () {
    'use strict';

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

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function arrayBufferToBase64(buf) {
        var binary = '';
        var bytes = new Uint8Array(buf);
        var chunk = 0x8000;
        for (var i = 0; i < bytes.length; i += chunk) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(binary);
    }

    function renderResult(container, kind, title, detailsHtml) {
        container.innerHTML =
            '<div class="verify-result ' + kind + '">' +
            '<h2>' + escapeHtml(title) + '</h2>' +
            '<div>' + detailsHtml + '</div>' +
            '</div>';
    }

    // ═══════════════════════════════════════════════════════
    //  Страница /verify — проверка .sig
    // ═══════════════════════════════════════════════════════
    function initIndexPage() {
        var form = document.getElementById('verify-form');
        var contentInput = document.getElementById('verify-content');
        var sigInput = document.getElementById('verify-sig');
        var contentName = document.getElementById('content-name');
        var sigName = document.getElementById('sig-name');
        var btn = document.getElementById('verify-btn');
        var result = document.getElementById('verify-result');

        contentInput.addEventListener('change', function () {
            contentName.textContent = contentInput.files.length
                ? contentInput.files[0].name
                : 'Файл не выбран';
        });
        sigInput.addEventListener('change', function () {
            sigName.textContent = sigInput.files.length
                ? sigInput.files[0].name
                : 'Файл не выбран';
        });

        form.addEventListener('submit', async function (e) {
            e.preventDefault();
            if (!contentInput.files.length || !sigInput.files.length) {
                renderResult(result, 'err', 'Не выбраны файлы',
                    'Загрузите документ и файл подписи (.sig).');
                return;
            }
            btn.disabled = true;
            btn.textContent = 'Проверяем…';
            result.innerHTML = '';

            try {
                var contentBuf = await contentInput.files[0].arrayBuffer();
                var sigText = await sigInput.files[0].text();
                var sigB64 = sigText
                    .replace(/-----BEGIN[^-]*-----/g, '')
                    .replace(/-----END[^-]*-----/g, '')
                    .replace(/\s+/g, '')
                    .replace(/[^A-Za-z0-9+/=]/g, '');

                var resp = await fetch('/api/verify/sig', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        contentBase64: arrayBufferToBase64(contentBuf),
                        signatureBase64: sigB64
                    })
                });
                var data = await resp.json();

                if (data.valid) {
                    var info = data.signersInfo
                        ? '<pre style="white-space:pre-wrap;font-size:12px;color:#475569;margin:10px 0 0;">'
                            + escapeHtml(data.signersInfo) + '</pre>'
                        : '';
                    renderResult(result, 'ok', '✓ Подпись действительна',
                        'Криптографическая подпись сходится с содержимым документа.'
                        + (data.signerSubject
                            ? '<br><br><b>Подписант:</b> ' + escapeHtml(data.signerSubject)
                            : '')
                        + (data.signerSerial
                            ? '<br><b>Серийный номер:</b> ' + escapeHtml(data.signerSerial)
                            : '')
                        + info);
                } else {
                    renderResult(result, 'err', '✗ Подпись недействительна',
                        escapeHtml(data.error || 'Не удалось проверить подпись.'));
                }
            } catch (err) {
                renderResult(result, 'err', 'Ошибка проверки',
                    escapeHtml(err.message || String(err)));
            } finally {
                btn.disabled = false;
                btn.textContent = '✓ Проверить подпись';
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    //  Страница /verify/{uuid} — инфо о документе
    // ═══════════════════════════════════════════════════════
    async function initDocumentPage(uuid) {
        var loading = document.getElementById('verify-loading');
        var output = document.getElementById('verify-output');
        var timeEl = document.getElementById('verify-time');

        if (timeEl) {
            timeEl.textContent = new Date().toLocaleString('ru-RU');
        }

        if (!uuid) {
            loading.style.display = 'none';
            output.style.display = 'block';
            renderResult(output, 'err', 'Неверная ссылка',
                'UUID документа не указан.');
            return;
        }

        try {
            var resp = await fetch('/api/verify/' + encodeURIComponent(uuid));
            loading.style.display = 'none';
            output.style.display = 'block';

            if (resp.status === 404) {
                renderResult(output, 'err', 'Документ не найден',
                    'Возможно, ссылка устарела или документ был удалён.');
                return;
            }
            if (!resp.ok) {
                renderResult(output, 'err', 'Ошибка ' + resp.status,
                    'Не удалось получить информацию о документе.');
                return;
            }

            var data = await resp.json();
            var sigs = data.signatures || [];
            var hasSigs = sigs.length > 0;
            var allValid = data.valid && hasSigs;

            var kind = allValid ? 'ok' : (hasSigs ? 'warn' : 'warn');
            var title = allValid
                ? '✓ Подпись действительна'
                : (hasSigs
                    ? '⚠ Документ подписан, но есть замечания'
                    : '⚠ Документ без подписи');

            var html = '';

            html += '<div class="verify-result ' + kind + '">';
            html += '<h2>' + escapeHtml(title) + '</h2>';
            html += '<div>Документ зарегистрирован в системе ПАО «НБД-Банк». '
                + (hasSigs
                    ? 'Ниже — все электронные подписи документа.'
                    : 'На момент проверки документ не подписан.')
                + '</div>';
            html += '</div>';

            // Метаданные документа
            html += '<div class="verify-card">';
            html += '<h2 style="margin:0 0 16px;font-size:17px;">Информация о документе</h2>';
            html += '<dl class="verify-dl">';
            html += '<dt>Название</dt><dd>' + escapeHtml(data.documentName || '—') + '</dd>';
            html += '<dt>Идентификатор</dt><dd><code>'
                + escapeHtml(String(data.documentId)) + '</code></dd>';
            html += '<dt>Загружен</dt><dd>' + fmtDate(data.uploadedAt) + '</dd>';
            html += '<dt>Подписей</dt><dd><b>' + sigs.length + '</b></dd>';
            html += '</dl>';
            html += '</div>';

            // Список подписей
            if (hasSigs) {
                html += '<div class="verify-card">';
                html += '<h2 style="margin:0 0 12px;font-size:17px;">'
                    + 'Электронные подписи</h2>';
                html += '<div class="verify-sig-list">';
                sigs.forEach(function (s, i) {
                    html += '<div class="verify-sig-item">';
                    html += '<div class="sig-head">';
                    html += '<div class="sig-number">' + (i + 1) + '</div>';
                    html += '<div class="sig-subject">'
                        + escapeHtml(s.subject || 'Подписант') + '</div>';
                    html += '</div>';
                    html += '<dl class="verify-dl">';
                    html += '<dt>Дата подписания</dt><dd>'
                        + fmtDate(s.signedAt) + '</dd>';
                    if (s.serial) {
                        html += '<dt>Серийный номер</dt><dd><code>'
                            + escapeHtml(s.serial) + '</code></dd>';
                    }
                    if (s.applyPoa) {
                        html += '<dt>Доверенность</dt><dd>✓ применена</dd>';
                    }
                    html += '</dl>';
                    html += '</div>';
                });
                html += '</div>';
                html += '</div>';
            }

            output.innerHTML = html;
        } catch (e) {
            loading.style.display = 'none';
            output.style.display = 'block';
            renderResult(output, 'err', 'Ошибка',
                escapeHtml(e.message || String(e)));
        }
    }

    window.VerifyUI = {
        initIndexPage: initIndexPage,
        initDocumentPage: initDocumentPage
    };
})();
