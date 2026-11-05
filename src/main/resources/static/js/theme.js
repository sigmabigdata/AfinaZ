/**
 * Управление тёмной темой.
 * - Автоопределение по prefers-color-scheme при первом заходе
 * - Сохранение в localStorage
 * - Плавное переключение
 * - Триггер-кнопка в шапке
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'afina-theme';
    var THEMES = ['light', 'dark'];
    var current = 'light';

    function getInitial() {
        var saved = localStorage.getItem(STORAGE_KEY);
        if (saved && THEMES.indexOf(saved) >= 0) return saved;
        // Автоопределение
        if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
            return 'dark';
        }
        return 'light';
    }

    function apply(theme) {
        if (THEMES.indexOf(theme) < 0) theme = 'light';
        current = theme;
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(STORAGE_KEY, theme);
        updateButtons();
    }

    function toggle() {
        apply(current === 'dark' ? 'light' : 'dark');
    }

    function updateButtons() {
        document.querySelectorAll('.theme-toggle').forEach(function (btn) {
            btn.innerHTML = current === 'dark' ? '☀️' : '🌙';
            btn.title = current === 'dark' ? 'Светлая тема' : 'Тёмная тема';
        });
    }

    function injectButtons() {
        // Ищем, куда вставить: перед .cmdk-trigger (если есть), иначе — в header
        document.querySelectorAll('header.app-header, header.admin-header').forEach(function (header) {
            if (header.querySelector('.theme-toggle')) return;
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'theme-toggle';
            btn.addEventListener('click', toggle);

            var anchor = header.querySelector('.cmdk-trigger');
            if (anchor) {
                anchor.parentNode.insertBefore(btn, anchor);
            } else {
                var form = header.querySelector('form[action*="logout"]');
                if (form) {
                    form.parentNode.insertBefore(btn, form);
                } else {
                    header.appendChild(btn);
                }
            }
        });
        updateButtons();
    }

    function init() {
        // Применяем сразу, ещё до DOMContentLoaded, чтобы не было «вспышки»
        apply(getInitial());
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', injectButtons);
        } else {
            injectButtons();
        }
    }

    // Применяем тему ДО отрисовки — на случай если style.css ещё грузится
    if (document.documentElement) {
        document.documentElement.setAttribute('data-theme', getInitial());
    }

    init();
    window.ThemeUI = {
        toggle: toggle,
        apply: apply,
        get: function () { return current; }
    };
})();
