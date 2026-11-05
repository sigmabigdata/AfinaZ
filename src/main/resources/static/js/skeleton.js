/**
 * Skeleton-загрузка для асинхронных операций.
 *
 * Использование:
 *   Skeleton.wrapWithLoader(container, asyncFn)
 *   // или
 *   Skeleton.show(container, 'card'|'table'|'list')
 *   Skeleton.hide(container)
 */
(function () {
    'use strict';

    var originalContent = new WeakMap();

    function show(el, kind) {
        if (!el || !(el instanceof HTMLElement)) return;
        if (originalContent.has(el)) return;  // уже показан

        originalContent.set(el, el.innerHTML);
        kind = kind || 'card';

        if (kind === 'table') {
            el.innerHTML = renderTableSkeleton(5);
        } else if (kind === 'list') {
            el.innerHTML = renderListSkeleton(3);
        } else {
            el.innerHTML = renderCardSkeleton();
        }
    }

    function hide(el) {
        if (!el) return;
        if (originalContent.has(el)) {
            el.innerHTML = originalContent.get(el);
            originalContent.delete(el);
        }
    }

    function renderCardSkeleton() {
        return '<div class="skeleton-card">'
            + '<div class="skeleton skeleton-title"></div>'
            + '<div class="skeleton skeleton-text wide"></div>'
            + '<div class="skeleton skeleton-text"></div>'
            + '<div class="skeleton skeleton-text short"></div>'
            + '</div>';
    }

    function renderTableSkeleton(rows) {
        var out = '<div style="padding: 16px;">';
        for (var i = 0; i < rows; i++) {
            out += '<div style="display:flex;gap:12px;padding:10px 0;border-bottom:1px solid var(--border-color);">'
                + '<div class="skeleton skeleton-avatar" style="width:32px;height:32px;"></div>'
                + '<div style="flex:1;">'
                + '<div class="skeleton skeleton-text wide"></div>'
                + '<div class="skeleton skeleton-text short"></div>'
                + '</div></div>';
        }
        out += '</div>';
        return out;
    }

    function renderListSkeleton(items) {
        var out = '';
        for (var i = 0; i < items; i++) {
            out += '<div class="skeleton-card" style="margin-bottom:12px;">'
                + '<div class="skeleton skeleton-text wide"></div>'
                + '<div class="skeleton skeleton-text short"></div>'
                + '</div>';
        }
        return out;
    }

    /**
     * Обёртка над async-функцией с автоматическим skeleton.
     */
    async function wrapWithLoader(el, kind, asyncFn) {
        show(el, kind);
        try {
            return await asyncFn();
        } finally {
            hide(el);
        }
    }

    window.Skeleton = {
        show: show,
        hide: hide,
        wrapWithLoader: wrapWithLoader
    };
})();
