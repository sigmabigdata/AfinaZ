/**
 * Модалка прогресса подписания документа.
 *
 * Использование:
 *   SignProgress.open({ title: 'Подписание документа', subtitle: '...' });
 *   SignProgress.active(0);   // шаг 0 — ожидание
 *   SignProgress.done(0);     // шаг 0 — успех
 *   SignProgress.error(0, '...');  // шаг 0 — ошибка
 *   SignProgress.success();   // все успешно, авто-закрытие
 *   SignProgress.fail('...'); // где-то ошибка, показать крестик
 *   SignProgress.close();
 */
(function () {
    'use strict';

    var overlay, card, stepsEl, actionsEl;
    var steps = [];
    var autoCloseTimer = null;

    var DEFAULT_STEPS = [
        { icon: '🔍', title: 'Поиск сертификата', sub: 'Чтение из хранилища «Личные»' },
        { icon: '🔑', title: 'Ввод PIN-кода', sub: 'Разблокировка закрытого ключа' },
        { icon: '🖋', title: 'Подписание', sub: 'Формирование CAdES-BES' },
        { icon: '✓', title: 'Проверка на сервере', sub: 'Криптопроверка и цепочка' }
    ];

    function init() {
        if (overlay) return;

        overlay = document.createElement('div');
        overlay.className = 'sign-progress-overlay';
        overlay.innerHTML = ''
            + '<div class="sign-progress-card">'
            +   '<h3 class="title">Подписание документа</h3>'
            +   '<p class="sub">Не закрывайте окно — процесс идёт</p>'
            +   '<div class="sign-steps"></div>'
            +   '<div class="sign-progress-actions" style="display:none;">'
            +     '<button type="button" class="btn-ghost btn-sm js-close">Закрыть</button>'
            +   '</div>'
            + '</div>';
        document.body.appendChild(overlay);

        card = overlay.querySelector('.sign-progress-card');
        stepsEl = overlay.querySelector('.sign-steps');
        actionsEl = overlay.querySelector('.sign-progress-actions');

        actionsEl.querySelector('.js-close').addEventListener('click', close);
    }

    function renderSteps(list) {
        steps = list.map(function (s) { return Object.assign({ state: 'pending' }, s); });
        stepsEl.innerHTML = '';
        steps.forEach(function (s, i) {
            var el = document.createElement('div');
            el.className = 'sign-step';
            el.dataset.step = i;
            el.innerHTML = ''
                + '<div class="sign-step-icon">' + s.icon + '</div>'
                + '<div class="sign-step-body">'
                +   '<div class="sign-step-title">' + s.title + '</div>'
                +   '<div class="sign-step-sub">' + s.sub + '</div>'
                + '</div>'
                + '<div class="sign-step-status"></div>';
            stepsEl.appendChild(el);
        });
    }

    function setStepState(i, state, message) {
        if (i < 0 || i >= steps.length) return;
        steps[i].state = state;
        var el = stepsEl.querySelector('[data-step="' + i + '"]');
        if (!el) return;
        el.classList.remove('active', 'done', 'error');
        var status = el.querySelector('.sign-step-status');
        status.innerHTML = '';

        if (state === 'active') {
            el.classList.add('active');
            status.innerHTML = '<div class="spinner-sm"></div>';
        } else if (state === 'done') {
            el.classList.add('done');
            status.innerHTML = '<span class="check">✓</span>';
        } else if (state === 'error') {
            el.classList.add('error');
            status.innerHTML = '<span class="cross">✗</span>';
            if (message) {
                var sub = el.querySelector('.sign-step-sub');
                if (sub) sub.textContent = message;
            }
        }
    }

    function open(opts) {
        init();
        opts = opts || {};
        card.querySelector('.title').textContent = opts.title || 'Подписание документа';
        card.querySelector('.sub').textContent = opts.subtitle || 'Не закрывайте окно — процесс идёт';
        renderSteps(opts.steps || DEFAULT_STEPS);
        actionsEl.style.display = 'none';
        overlay.classList.add('visible');
        if (autoCloseTimer) { clearTimeout(autoCloseTimer); autoCloseTimer = null; }
    }

    function active(i) { setStepState(i, 'active'); }
    function done(i)   { setStepState(i, 'done'); }
    function error(i, msg) { setStepState(i, 'error', msg); }

    function success(message) {
        // все шаги — done
        steps.forEach(function (_, i) { done(i); });
        card.querySelector('.sub').textContent = message || 'Документ успешно подписан';
        // авто-закрытие через 1.2с
        autoCloseTimer = setTimeout(close, 1200);
    }

    function fail(message) {
        card.querySelector('.sub').textContent = message || 'Не удалось подписать документ';
        // показываем кнопку «Закрыть»
        actionsEl.style.display = 'flex';
    }

    function close() {
        if (overlay) overlay.classList.remove('visible');
        if (autoCloseTimer) { clearTimeout(autoCloseTimer); autoCloseTimer = null; }
    }

    window.SignProgress = {
        open: open,
        active: active,
        done: done,
        error: error,
        success: success,
        fail: fail,
        close: close
    };
})();
