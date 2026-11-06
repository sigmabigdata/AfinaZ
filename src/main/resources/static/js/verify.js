/**
 * Публичная верификация подписи.
 * Строгая проверка: целостность, цепочка, truststore, CRL, EKU.
 * Красивый UI с чек-листом + скачивание PDF-отчёта.
 */
(function () {
    'use strict';

    function fmtDate(iso) {
        if (!iso) return '—';
        try {
            var d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            var p = function (n) { return String(n).padStart(2, '0'); };
            return p(d.getDate()) + '.' + p(d.getMonth() + 1) + '.' + d.getFullYear();
        } catch (e) { return iso; }
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
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

    // ═══════════════════════════════════════════════════════
    //  /verify — проверка .sig
    // ═══════════════════════════════════════════════════════
    function initIndexPage() {
        var form = document.getElementById('verify-form');
        var contentInput = document.getElementById('verify-content');
        var sigInput = document.getElementById('verify-sig');
        var contentName = document.getElementById('content-name');
        var sigName = document.getElementById('sig-name');
        var btn = document.getElementById('verify-btn');
        var result = document.getElementById('verify-result');

        var lastState = null;  // для скачивания отчёта

        contentInput.addEventListener('change', function () {
            contentName.textContent = contentInput.files.length
                ? contentInput.files[0].name : 'Файл не выбран';
        });
        sigInput.addEventListener('change', function () {
            sigName.textContent = sigInput.files.length
                ? sigInput.files[0].name : 'Файл не выбран';
        });

        form.addEventListener('submit', async function (e) {
            e.preventDefault();
            if (!contentInput.files.length || !sigInput.files.length) {
                result.innerHTML = '<div class="verify-alert err">Загрузите документ и .sig</div>';
                return;
            }
            btn.disabled = true;
            btn.textContent = 'Проверяем…';
            result.innerHTML = '<div class="verify-loading"><div class="spinner"></div><div>Проверка подписи…</div></div>';

            try {
                var contentBuf = await contentInput.files[0].arrayBuffer();
                var sigBuf = await sigInput.files[0].arrayBuffer();
                var contentB64 = arrayBufferToBase64(contentBuf);
                var sigB64 = arrayBufferToBase64(sigBuf);

                var resp = await fetch('/api/verify/sig', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ contentBase64: contentB64, signatureBase64: sigB64 })
                });
                var data = await resp.json();

                lastState = {
                    contentB64: contentB64,
                    sigB64: sigB64,
                    fileName: contentInput.files[0].name
                };

                renderResult(result, data, lastState);
            } catch (err) {
                result.innerHTML = '<div class="verify-alert err">Ошибка: '
                    + esc(err.message || String(err)) + '</div>';
            } finally {
                btn.disabled = false;
                btn.textContent = '✓ Проверить подпись';
            }
        });
    }

    function renderResult(container, data, state) {
        var valid = data.valid;
        var checks = data.checks || [];

        var html = '';

        // ─── Итог (как на Госуслугах) ───
        html += '<div class="gos-report">';

        html += '<h1 class="gos-title">Отчёт о проверке '
            + 'квалифицированной электронной подписи</h1>';

        // Главный статус
        html += '<div class="gos-status ' + (valid ? 'ok' : 'err') + '">';
        html += '<div class="gos-status-label">Подпись</div>';
        html += '<div class="gos-status-value">'
            + (valid ? 'ДЕЙСТВИТЕЛЬНА' : 'НЕДЕЙСТВИТЕЛЬНА') + '</div>';
        html += '</div>';

        // ─── Подпись N ───
        html += '<h2 class="gos-section">Подпись 1</h2>';

        // Статус ЭП
        html += '<div class="gos-row">';
        html += '<div class="gos-label">Статус ЭП</div>';
        html += '<div class="gos-value ' + (valid ? 'ok' : 'err') + '">';
        html += valid ? 'Электронная подпись верна' : 'Электронная подпись неверна';
        html += '</div></div>';

        // Статус сертификата
        var certCheck = checks.find(function (x) { return x.name.indexOf('Срок действия сертификата') >= 0; });
        html += '<div class="gos-row">';
        html += '<div class="gos-label">Статус сертификата ЭП</div>';
        html += '<div class="gos-value ' + (certCheck && certCheck.passed ? 'ok' : 'err') + '">';
        html += certCheck && certCheck.passed ? 'ДЕЙСТВИТЕЛЕН' : 'НЕДЕЙСТВИТЕЛЕН';
        html += '</div></div>';

        // Владелец сертификата
        if (data.signerFullSubject) {
            html += '<div class="gos-row">';
            html += '<div class="gos-label">Владелец сертификата</div>';
            html += '<div class="gos-value mono">' + esc(data.signerFullSubject) + '</div>';
            html += '</div>';
        }

        // Издатель
        if (data.signerIssuerFull) {
            html += '<div class="gos-row">';
            html += '<div class="gos-label">Издатель сертификата</div>';
            html += '<div class="gos-value mono">' + esc(data.signerIssuerFull) + '</div>';
            html += '</div>';
        }

        // Серийный номер
        if (data.signerSerial) {
            html += '<div class="gos-row">';
            html += '<div class="gos-label">Серийный номер</div>';
            html += '<div class="gos-value mono">' + esc(data.signerSerial) + '</div>';
            html += '</div>';
        }

        // Отпечаток
        if (data.signerThumbprint) {
            html += '<div class="gos-row">';
            html += '<div class="gos-label">Отпечаток</div>';
            html += '<div class="gos-value mono">' + esc(data.signerThumbprint) + '</div>';
            html += '</div>';
        }

        // Действует
        if (data.signerNotBefore && data.signerNotAfter) {
            html += '<div class="gos-row">';
            html += '<div class="gos-label">Действует</div>';
            html += '<div class="gos-value">с ' + fmtDate(data.signerNotBefore)
                + ' по ' + fmtDate(data.signerNotAfter) + '</div>';
            html += '</div>';
        }

        // Дата проверки
        html += '<div class="gos-row">';
        html += '<div class="gos-label">Дата проверки</div>';
        html += '<div class="gos-value">' + new Date().toLocaleString('ru-RU') + '</div>';
        html += '</div>';

        // Кнопка скачать
        if (state) {
            html += '<div class="gos-actions">';
            html += '<button type="button" class="gos-download" onclick="__downloadReport()">';
            html += '📥 Скачать отчёт</button>';
            html += '</div>';
        }

        // ─── Детальный чек-лист (свернуто) ───
        if (checks.length > 0) {
            html += '<details class="gos-details">';
            html += '<summary>Все проверки (' + checks.filter(function(x){return x.passed;}).length
                + ' из ' + checks.length + ')</summary>';
            html += '<div class="verify-checks" style="margin-top:12px;">';
            checks.forEach(function (c) {
                var ic = c.passed ? '✓' : '✗';
                var cl = c.passed ? 'passed' : 'failed';
                html += '<div class="verify-check ' + cl + '">';
                html += '<div class="verify-check-status">' + ic + '</div>';
                html += '<div class="verify-check-body">';
                html += '<div class="verify-check-name">' + esc(c.name) + '</div>';
                if (c.details) {
                    html += '<div class="verify-check-details">' + esc(c.details) + '</div>';
                }
                html += '</div></div>';
            });
            html += '</div></details>';
        }

        // ─── Цепочка ───
        if (data.chain && data.chain.length > 0) {
            html += '<details class="gos-details">';
            html += '<summary>Цепочка сертификатов</summary>';
            html += '<ol class="verify-chain" style="margin-top:12px;">';
            data.chain.forEach(function (cn) {
                html += '<li>' + esc(cn) + '</li>';
            });
            html += '</ol></details>';
        }

        html += '</div>';  // /gos-report

        container.innerHTML = html;
        window.__verifyState = state;
    }

    window.__downloadReport = async function () {
        var s = window.__verifyState;
        if (!s) return;
        try {
            var resp = await fetch('/api/verify/sig/report', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    contentBase64: s.contentB64,
                    signatureBase64: s.sigB64,
                    fileName: s.fileName
                })
            });
            if (!resp.ok) {
                alert('Не удалось сгенерировать отчёт (HTTP ' + resp.status + ')');
                return;
            }
            var blob = await resp.blob();
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = 'verification-report.pdf';
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(url);
        } catch (e) {
            alert('Ошибка: ' + e.message);
        }
    };

    // ═══════════════════════════════════════════════════════
    //  /verify/{uuid} — инфо о документе
    // ═══════════════════════════════════════════════════════
    async function initDocumentPage(uuid) {
        var loading = document.getElementById('verify-loading');
        var output = document.getElementById('verify-output');
        var timeEl = document.getElementById('verify-time');

        if (timeEl) timeEl.textContent = new Date().toLocaleString('ru-RU');

        if (!uuid) {
            loading.style.display = 'none';
            output.style.display = 'block';
            output.innerHTML = '<div class="verify-alert err">UUID документа не указан.</div>';
            return;
        }

        try {
            var resp = await fetch('/api/verify/' + encodeURIComponent(uuid));
            loading.style.display = 'none';
            output.style.display = 'block';

            if (resp.status === 404) {
                output.innerHTML = '<div class="verify-alert err">'
                    + 'Документ не найден или ссылка устарела.</div>';
                return;
            }
            if (!resp.ok) {
                output.innerHTML = '<div class="verify-alert err">'
                    + 'Ошибка ' + resp.status + '</div>';
                return;
            }

            var data = await resp.json();
            var sigs = data.signatures || [];
            var hasSigs = sigs.length > 0;
            var allValid = data.valid && hasSigs;

            var cls = allValid ? 'ok' : (hasSigs ? 'warn' : 'warn');
            var icon = allValid ? '✓' : (hasSigs ? '⚠' : '⚠');
            var title = allValid
                ? 'Подпись действительна'
                : (hasSigs ? 'Документ подписан' : 'Документ без подписи');

            var html = '';
            html += '<div class="verify-summary ' + cls + '">';
            html += '<div class="verify-summary-icon">' + icon + '</div>';
            html += '<div class="verify-summary-body">';
            html += '<h2>' + esc(title) + '</h2>';
            html += '<p>Документ зарегистрирован в системе ПАО «НБД-Банк»</p>';
            html += '</div></div>';

            html += '<div class="verify-section">';
            html += '<h3 class="verify-section-title">Информация о документе</h3>';
            html += '<dl class="verify-signer-dl">';
            html += '<dt>Название</dt><dd>' + esc(data.documentName || '—') + '</dd>';
            html += '<dt>Идентификатор</dt><dd><code>#' + esc(String(data.documentId)) + '</code></dd>';
            html += '<dt>Загружен</dt><dd>' + fmtDate(data.uploadedAt) + '</dd>';
            html += '<dt>Подписей</dt><dd><b>' + sigs.length + '</b></dd>';
            html += '</dl></div>';

            if (hasSigs) {
                html += '<div class="verify-section">';
                html += '<h3 class="verify-section-title">Подписи</h3>';
                html += '<div class="verify-checks">';
                sigs.forEach(function (s, i) {
                    html += '<div class="verify-check passed">';
                    html += '<div class="verify-check-status">' + (i + 1) + '</div>';
                    html += '<div class="verify-check-body">';
                    html += '<div class="verify-check-name">' + esc(s.subject || 'Подписант') + '</div>';
                    html += '<div class="verify-check-details">'
                        + fmtDate(s.signedAt)
                        + (s.serial ? ' · serial=' + esc(s.serial) : '')
                        + (s.applyPoa ? ' · с доверенностью' : '')
                        + '</div></div></div>';
                });
                html += '</div></div>';
            }

            output.innerHTML = html;
        } catch (e) {
            loading.style.display = 'none';
            output.style.display = 'block';
            output.innerHTML = '<div class="verify-alert err">Ошибка: ' + esc(e.message) + '</div>';
        }
    }

    window.VerifyUI = {
        initIndexPage: initIndexPage,
        initDocumentPage: initDocumentPage
    };
})();
