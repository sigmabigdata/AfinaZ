/**
 * Модалка "История документа" (timeline).
 */
(function () {
    'use strict';

    var overlay, titleEl, subEl, bodyEl;

    function init() {
        if (overlay) return;

        overlay = document.createElement('div');
        overlay.className = 'timeline-modal-overlay';
        overlay.innerHTML = ''
            + '<div class="timeline-modal-card">'
            +   '<div class="timeline-modal-header">'
            +     '<div>'
            +       '<h2 id="tl-title">История документа</h2>'
            +       '<div class="sub" id="tl-sub">Загрузка…</div>'
            +     '</div>'
            +     '<button type="button" class="btn-ghost btn-sm" id="tl-close">✕</button>'
            +   '</div>'
            +   '<div class="timeline-modal-body" id="tl-body">'
            +     '<div class="timeline-empty"><div class="icon">⏳</div>Загрузка…</div>'
            +   '</div>'
            + '</div>';
        document.body.appendChild(overlay);

        titleEl = overlay.querySelector('#tl-title');
        subEl = overlay.querySelector('#tl-sub');
        bodyEl = overlay.querySelector('#tl-body');

        overlay.querySelector('#tl-close').addEventListener('click', close);
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) close();
        });
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function fmt(iso) {
        if (!iso) return '—';
        var d = new Date(iso);
        if (isNaN(d.getTime())) return iso;
        var pad = function (n) { return String(n).padStart(2, '0'); };
        return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + '.' + d.getFullYear()
            + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    async function open(docId) {
        init();
        titleEl.textContent = 'История документа #' + docId;
        subEl.textContent = 'Загрузка…';
        bodyEl.innerHTML = '<div class="timeline-empty"><div class="icon">⏳</div>Загрузка…</div>';
        overlay.classList.add('visible');

        try {
            var resp = await fetch('/api/documents/' + docId + '/timeline', {
                credentials: 'same-origin'
            });
            if (!resp.ok) {
                bodyEl.innerHTML = '<div class="timeline-empty"><div class="icon">⚠</div>'
                    + 'Не удалось загрузить (HTTP ' + resp.status + ')</div>';
                return;
            }
            var data = await resp.json();
            titleEl.textContent = data.documentName || ('Документ #' + docId);
            subEl.textContent = data.events.length + ' событий';

            if (!data.events || data.events.length === 0) {
                bodyEl.innerHTML = '<div class="timeline-empty"><div class="icon">📭</div>'
                    + 'Событий пока нет</div>';
                return;
            }

            var html = '<div class="timeline-list">';
            data.events.forEach(function (e) {
                html += '<div class="timeline-item">';
                html += '<div class="timeline-icon">' + esc(e.icon || '•') + '</div>';
                html += '<div class="timeline-content">';
                html += '<div class="timeline-title">' + esc(e.label || e.type) + '</div>';
                html += '<div class="timeline-meta">';
                html += '<span>' + fmt(e.time) + '</span>';
                if (e.actor) {
                    html += '<span>· <span class="actor">' + esc(e.actor) + '</span></span>';
                }
                if (e.role) {
                    html += '<span>· ' + esc(e.role) + '</span>';
                }
                html += '</div>';
                if (e.details) {
                    html += '<div class="timeline-details">' + esc(e.details) + '</div>';
                }
                html += '</div></div>';
            });
            html += '</div>';
            bodyEl.innerHTML = html;
        } catch (err) {
            bodyEl.innerHTML = '<div class="timeline-empty"><div class="icon">⚠</div>'
                + 'Ошибка: ' + esc(err.message) + '</div>';
        }
    }

    function close() {
        if (overlay) overlay.classList.remove('visible');
    }

    // Делегирование кликов по кнопкам с классом .btn-timeline
    document.addEventListener('click', function (e) {
        var btn = e.target.closest('.btn-timeline');
        if (!btn) return;
        e.preventDefault();
        var id = btn.dataset.id;
        if (id) open(Number(id));
    });

    window.TimelineUI = { open: open };
})();
