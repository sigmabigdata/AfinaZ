/**
 * Command Palette (⌘K / Ctrl+K).
 *
 * Открывается по ⌘K / Ctrl+K, ищет через /api/search.
 * Навигация: ↑↓, выбор: Enter, закрыть: Esc.
 *
 * Также регистрирует hotkeys "g + <буква>" для быстрого перехода.
 */
(function () {
    'use strict';

    var overlay, input, bodyEl;
    var isOpen = false;
    var debounceTimer = null;
    var results = { pages: [], actions: [], users: [], documents: [] };
    var selectedIndex = -1;
    var flat = [];  // плоский список для навигации

    // Hotkey sequence: "g" then letter
    var gPressed = false;
    var gTimer = null;

    function init() {
        if (overlay) return;

        overlay = document.createElement('div');
        overlay.className = 'cmdk-overlay';
        overlay.innerHTML = ''
            + '<div class="cmdk-card">'
            +   '<div class="cmdk-input-wrap">'
            +     '<span class="icon">🔍</span>'
            +     '<input type="text" class="cmdk-input" id="cmdk-input"'
            +           ' placeholder="Поиск страниц, клиентов, документов…"'
            +           ' autocomplete="off" spellcheck="false"/>'
            +     '<span class="cmdk-hint">ESC</span>'
            +   '</div>'
            +   '<div class="cmdk-body" id="cmdk-body"></div>'
            +   '<div class="cmdk-footer">'
            +     '<span class="hotkey"><span class="key">↑</span><span class="key">↓</span> навигация</span>'
            +     '<span class="hotkey"><span class="key">↵</span> выбрать</span>'
            +     '<span class="hotkey"><span class="key">g</span>+<span class="key">d</span> дашборд</span>'
            +     '<span class="hotkey"><span class="key">g</span>+<span class="key">u</span> клиенты</span>'
            +     '<span class="hotkey"><span class="key">g</span>+<span class="key">r</span> карточка</span>'
            +   '</div>'
            + '</div>';
        document.body.appendChild(overlay);

        input = overlay.querySelector('#cmdk-input');
        bodyEl = overlay.querySelector('#cmdk-body');

        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) close();
        });

        input.addEventListener('input', function () {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(search, 180);
        });

        input.addEventListener('keydown', onKeyDown);

        // Глобальные hotkeys
        document.addEventListener('keydown', onGlobalKeyDown);

        // Кнопки-триггеры
        document.querySelectorAll('.cmdk-trigger').forEach(function (btn) {
            btn.addEventListener('click', open);
        });
    }

    function onGlobalKeyDown(e) {
        // ⌘K / Ctrl+K — открыть
        if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
            e.preventDefault();
            if (isOpen) close(); else open();
            return;
        }

        // Esc — закрыть
        if (e.key === 'Escape' && isOpen) {
            e.preventDefault();
            close();
            return;
        }

        // Не реагируем на hotkeys, если открыт input/textarea
        var tag = (e.target.tagName || '').toLowerCase();
        if (tag === 'input' || tag === 'textarea' || e.target.isContentEditable) return;
        if (isOpen) return;

        // g + letter
        if (gPressed) {
            var map = {
                'd': '/admin',
                'u': '/admin/users',
                't': '/admin/templates',
                'a': '/admin/audit',
                's': '/admin/settings',
                'p': '/admin/poa',
                'm': '/admin/monitor'
            };
            var isAdmin = location.pathname.startsWith('/admin');
            var key = e.key.toLowerCase();
            var dest = map[key];

            // g+r — вернуться в карточку текущего клиента (если открыт /admin/users/{id}/...)
            if (key === 'r' && isAdmin) {
                var m = location.pathname.match(/^\/admin\/users\/(\d+)/);
                dest = m ? '/admin/users/' + m[1] : '/admin/users';
            }

            gPressed = false;
            clearTimeout(gTimer);
            if (dest) {
                // Для клиента "g d" ведёт на /dashboard
                if (!isAdmin && key === 'd') dest = '/dashboard';
                e.preventDefault();
                location.href = dest;
            }
            return;
        }

        if (e.key === 'g') {
            gPressed = true;
            clearTimeout(gTimer);
            gTimer = setTimeout(function () { gPressed = false; }, 1500);
        }
    }

    function onKeyDown(e) {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            selectedIndex = Math.min(selectedIndex + 1, flat.length - 1);
            renderSelection();
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            selectedIndex = Math.max(selectedIndex - 1, -1);
            renderSelection();
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (selectedIndex >= 0 && flat[selectedIndex]) {
                location.href = flat[selectedIndex].url;
            }
        }
    }

    function open() {
        init();
        overlay.classList.add('visible');
        isOpen = true;
        input.value = '';
        input.focus();
        selectedIndex = -1;
        // Загружаем базовый список (без запроса)
        search('');
    }

    function close() {
        if (overlay) overlay.classList.remove('visible');
        isOpen = false;
    }

    async function search(q) {
        try {
            var params = new URLSearchParams();
            params.set('limit', '10');
            if (q && q.trim()) params.set('q', q.trim());
            var url = '/api/search?' + params.toString();
            console.log('[cmdk] search:', q, '| URL:', url);
            var resp = await fetch(url, { credentials: 'same-origin' });
            if (!resp.ok) {
                var errText = '';
                try { errText = await resp.text(); } catch (ex) {}
                console.error('[cmdk] HTTP', resp.status, errText);
                bodyEl.innerHTML = '<div class="cmdk-empty">Ошибка: HTTP '
                    + resp.status + (errText ? ' — ' + escapeHtml(errText.substring(0, 200)) : '')
                    + '</div>';
                return;
            }
            var data = await resp.json();
            results = {
                pages: data.pages || [],
                actions: data.actions || [],
                users: data.users || [],
                documents: data.documents || []
            };
            render();
        } catch (e) {
            bodyEl.innerHTML = '<div class="cmdk-empty">Ошибка: ' + escapeHtml(e.message) + '</div>';
        }
    }

    function render() {
        bodyEl.innerHTML = '';
        flat = [];
        selectedIndex = -1;

        var groups = [
            { key: 'pages', title: 'Страницы' },
            { key: 'actions', title: 'Действия' },
            { key: 'users', title: 'Клиенты' },
            { key: 'documents', title: 'Документы' }
        ];

        var totalItems = 0;
        groups.forEach(function (g) {
            var items = results[g.key] || [];
            if (items.length === 0) return;
            totalItems += items.length;

            var title = document.createElement('div');
            title.className = 'cmdk-group-title';
            title.textContent = g.title;
            bodyEl.appendChild(title);

            items.forEach(function (item) {
                var a = document.createElement('a');
                a.className = 'cmdk-item';
                a.href = item.url || '#';
                a.dataset.flatIndex = flat.length;
                flat.push(item);

                a.innerHTML = ''
                    + '<div class="cmdk-icon">' + escapeHtml(item.icon || '•') + '</div>'
                    + '<div class="cmdk-item-text">'
                    +   '<div class="cmdk-item-label">' + escapeHtml(item.label) + '</div>'
                    +   (item.sub ? '<div class="cmdk-item-sub">' + escapeHtml(item.sub) + '</div>' : '')
                    + '</div>'
                    + (item.hotkey ? '<div class="cmdk-item-hotkey">' + escapeHtml(item.hotkey) + '</div>' : '');

                bodyEl.appendChild(a);
            });
        });

        if (totalItems === 0) {
            bodyEl.innerHTML = '<div class="cmdk-empty">'
                + 'Ничего не найдено. Попробуйте другой запрос.</div>';
        }
    }

    function renderSelection() {
        bodyEl.querySelectorAll('.cmdk-item').forEach(function (el) {
            var idx = Number(el.dataset.flatIndex);
            el.classList.toggle('selected', idx === selectedIndex);
        });
        // Прокрутить в видимую область
        if (selectedIndex >= 0) {
            var selectedEl = bodyEl.querySelector('[data-flat-index="' + selectedIndex + '"]');
            if (selectedEl) selectedEl.scrollIntoView({ block: 'nearest' });
        }
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    window.CommandPalette = { open: open, close: close };
})();
