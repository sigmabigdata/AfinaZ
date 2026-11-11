/**
 * AfinaZ Setup Wizard — вспомогательный JS.
 *
 * Используется в step5 (preflight). Остальные шаги — обычные HTML-формы,
 * отправляются браузером напрямую (POST /setup/step/N).
 */
(function () {
    'use strict';

    /**
     * Запустить preflight-проверки и отрендерить результаты.
     *
     * @param {string} containerId — id элемента, куда рендерить список
     * @param {string} summaryId   — id элемента для итогового alert
     * @param {string} nextBtnId   — id кнопки «Далее» (включится если все OK)
     * @param {string} rerunBtnId  — id кнопки «Перепроверить» (опционально)
     */
    async function runPreflight(containerId, summaryId, nextBtnId, rerunBtnId) {
        var container = document.getElementById(containerId);
        var summary = document.getElementById(summaryId);
        var nextBtn = document.getElementById(nextBtnId);
        var rerunBtn = rerunBtnId ? document.getElementById(rerunBtnId) : null;

        if (!container) return;

        container.innerHTML = '<div class="preflight-loading">⏳ Выполняю проверки…</div>';
        if (summary) summary.style.display = 'none';
        if (nextBtn) nextBtn.setAttribute('disabled', 'disabled');
        if (rerunBtn) rerunBtn.disabled = true;

        try {
            var resp = await fetch('/setup/preflight', { credentials: 'same-origin' });
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            var data = await resp.json();
            renderPreflight(container, summary, nextBtn, data);
        } catch (e) {
            container.innerHTML = '<div class="setup-alert setup-alert-err">'
                + '<span class="alert-icon">✗</span>'
                + '<span>Ошибка проверки: ' + escapeHtml(e.message) + '</span></div>';
        } finally {
            if (rerunBtn) rerunBtn.disabled = false;
        }
    }

    function renderPreflight(container, summary, nextBtn, data) {
        var checks = (data && data.checks) || [];
        var allOk = data && data.allOk;

        var html = '<div class="preflight-list">';
        checks.forEach(function (c) {
            var cls = c.ok ? 'ok' : 'err';
            var icon = c.ok ? '✓' : '✗';
            html += '<div class="preflight-item preflight-' + cls + '">'
                + '<span class="preflight-icon">' + icon + '</span>'
                + '<div class="preflight-body">'
                + '<div class="preflight-name">' + escapeHtml(c.name) + '</div>'
                + (c.message ? '<div class="preflight-message">' + escapeHtml(c.message) + '</div>' : '')
                + '</div></div>';
        });
        html += '</div>';
        container.innerHTML = html;

        if (summary) {
            summary.style.display = 'flex';
            if (allOk) {
                summary.className = 'setup-alert setup-alert-ok';
                summary.innerHTML = '<span class="alert-icon">✓</span>'
                    + '<span><b>Все проверки пройдены.</b> Можно продолжать.</span>';
                if (nextBtn) nextBtn.removeAttribute('disabled');
            } else {
                summary.className = 'setup-alert setup-alert-err';
                summary.innerHTML = '<span class="alert-icon">⚠</span>'
                    + '<span><b>Есть проблемы.</b> Проверьте сообщения выше — '
                    + 'некоторые из них некритичны (например, DNS ещё не распространился).</span>';
                // Разрешаем продолжить даже с ошибками — DNS может быть временным
                if (nextBtn) nextBtn.removeAttribute('disabled');
            }
        }
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    window.SetupWizard = {
        runPreflight: runPreflight
    };
})();
