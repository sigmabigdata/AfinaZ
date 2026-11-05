/**
 * Клиентская модалка «Документ из шаблона».
 *
 * Использование:
 *   TemplateUse.open();  // открыть модалку
 *
 * Модалка запрашивает /api/templates, показывает карточки.
 * При выборе — форма с полями, после submit — генерация PDF и reload.
 */
(function () {
    'use strict';

    var overlay, card, bodyEl;
    var templates = [];
    var currentTemplate = null;

    function init() {
        if (overlay) return;

        overlay = document.createElement('div');
        overlay.className = 'tpl-picker-overlay';
        overlay.innerHTML = ''
            + '<div class="tpl-picker-card">'
            +   '<div class="tpl-picker-head">'
            +     '<div>'
            +       '<h2>Новый документ из шаблона</h2>'
            +       '<p class="sub">Выберите готовый шаблон — заполните поля, и мы сгенерируем PDF</p>'
            +     '</div>'
            +     '<button type="button" class="btn-ghost btn-sm" id="tpl-close">✕</button>'
            +   '</div>'
            +   '<div id="tpl-picker-body"></div>'
            + '</div>';
        document.body.appendChild(overlay);
        bodyEl = overlay.querySelector('#tpl-picker-body');

        overlay.querySelector('#tpl-close').addEventListener('click', close);
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) close();
        });
    }

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    async function open() {
        init();
        bodyEl.innerHTML = '<div class="timeline-empty"><div class="icon">⏳</div>Загрузка шаблонов…</div>';
        overlay.classList.add('visible');
        currentTemplate = null;

        try {
            var resp = await fetch('/api/templates', { credentials: 'same-origin' });
            if (!resp.ok) {
                bodyEl.innerHTML = '<div class="alert err">Не удалось загрузить шаблоны '
                    + '(HTTP ' + resp.status + ')</div>';
                return;
            }
            var data = await resp.json();
            templates = data.templates || [];
            if (templates.length === 0) {
                bodyEl.innerHTML = '<div class="timeline-empty"><div class="icon">📭</div>'
                    + 'Шаблонов пока нет. Обратитесь к администратору банка.</div>';
                return;
            }
            renderList();
        } catch (e) {
            bodyEl.innerHTML = '<div class="alert err">Ошибка: ' + esc(e.message) + '</div>';
        }
    }

    function renderList() {
        var html = '<div class="tpl-picker-list">';
        templates.forEach(function (t) {
            html += '<div class="tpl-picker-item" data-id="' + t.id + '">';
            html += '<span class="tpl-icon">📄</span>';
            html += '<div class="tpl-name">' + esc(t.name) + '</div>';
            if (t.description) {
                html += '<div class="tpl-desc">' + esc(t.description) + '</div>';
            }
            var fieldCount = (t.fields || []).length;
            html += '<div class="tpl-desc" style="margin-top:6px;color:#2454ff;font-size:11px;">'
                + fieldCount + ' полей для заполнения</div>';
            html += '</div>';
        });
        html += '</div>';
        bodyEl.innerHTML = html;

        bodyEl.querySelectorAll('.tpl-picker-item').forEach(function (el) {
            el.addEventListener('click', function () {
                var id = Number(el.dataset.id);
                var tpl = templates.find(function (x) { return x.id === id; });
                if (tpl) renderForm(tpl);
            });
        });
    }

    function renderForm(tpl) {
        currentTemplate = tpl;

        // Список разделов (из глобального контекста — соберём из data-атрибутов страницы)
        var folders = window.__FOLDERS__ || [];

        var html = '<button type="button" class="tpl-picker-back" id="tpl-back">'
            + '← К выбору шаблона</button>';

        html += '<h3 style="margin:0 0 14px;font-size:16px;">' + esc(tpl.name) + '</h3>';

        // Раздел
        html += '<div class="form-field" style="margin-bottom:14px;">';
        html += '<label>Сохранить в раздел <span class="req">*</span></label>';
        if (folders.length === 0) {
            html += '<div class="alert err" style="margin:0;">'
                + 'Сначала создайте раздел (кнопка «+ Создать раздел»).</div>';
            bodyEl.innerHTML = html;
            bodyEl.querySelector('#tpl-back').addEventListener('click', renderList);
            return;
        }
        html += '<select id="tpl-folder" class="tpl-folder-select" required>';
        folders.forEach(function (f) {
            html += '<option value="' + f.id + '">' + esc(f.name) + '</option>';
        });
        html += '</select>';
        html += '</div>';

        // Поля шаблона
        html += '<div class="tpl-form-fields">';
        (tpl.fields || []).forEach(function (f) {
            html += '<div class="form-field">';
            html += '<label>' + esc(f.label || f.key);
            if (f.required) html += ' <span class="req">*</span>';
            html += '</label>';

            var val = f.defaultValue || '';
            if (f.type === 'date') {
                html += '<input type="date" data-key="' + esc(f.key) + '"'
                    + ' value="' + esc(val) + '"'
                    + (f.required ? ' required' : '') + '/>';
            } else if (f.type === 'textarea') {
                html += '<textarea data-key="' + esc(f.key) + '"'
                    + (f.required ? ' required' : '') + '>' + esc(val) + '</textarea>';
            } else {
                html += '<input type="text" data-key="' + esc(f.key) + '"'
                    + ' value="' + esc(val) + '"'
                    + (f.required ? ' required' : '') + '/>';
            }
            html += '</div>';
        });
        html += '</div>';

        // Кнопки
        html += '<div class="tpl-picker-actions">';
        html += '<button type="button" class="btn-ghost" id="tpl-cancel">Отмена</button>';
        html += '<button type="button" class="btn" id="tpl-submit">📄 Сгенерировать PDF</button>';
        html += '</div>';

        bodyEl.innerHTML = html;

        bodyEl.querySelector('#tpl-back').addEventListener('click', renderList);
        bodyEl.querySelector('#tpl-cancel').addEventListener('click', close);
        bodyEl.querySelector('#tpl-submit').addEventListener('click', submitForm);
    }

    async function submitForm() {
        var btn = bodyEl.querySelector('#tpl-submit');
        var folderSel = bodyEl.querySelector('#tpl-folder');
        if (!folderSel) return;

        var folderId = Number(folderSel.value);
        var values = {};
        var fields = currentTemplate.fields || [];

        // Собираем значения
        var missing = [];
        fields.forEach(function (f) {
            var el = bodyEl.querySelector('[data-key="' + f.key + '"]');
            var v = el ? el.value.trim() : '';
            if (f.required && !v) {
                missing.push(f.label || f.key);
            }
            values[f.key] = v;
        });

        if (missing.length > 0) {
            alert('Заполните обязательные поля:\n• ' + missing.join('\n• '));
            return;
        }

        btn.disabled = true;
        btn.textContent = 'Генерация…';

        try {
            var resp = await fetch('/api/templates/' + currentTemplate.id + '/generate', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ folderId: folderId, values: values })
            });
            var data = await resp.json().catch(function () { return {}; });
            if (resp.ok && data.documentId) {
                btn.textContent = '✓ Готово';
                setTimeout(function () {
                    close();
                    location.reload();
                }, 600);
            } else {
                alert('Ошибка: ' + (data.error || 'HTTP ' + resp.status));
                btn.disabled = false;
                btn.textContent = '📄 Сгенерировать PDF';
            }
        } catch (e) {
            alert('Ошибка: ' + e.message);
            btn.disabled = false;
            btn.textContent = '📄 Сгенерировать PDF';
        }
    }

    function close() {
        if (overlay) overlay.classList.remove('visible');
    }

    window.TemplateUse = { open: open };
})();
