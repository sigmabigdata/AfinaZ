/**
 * Редактор шаблонов документов.
 * Управляет списком полей (fields_json), вставляет {{key}} в HTML по клику на чип,
 * рендерит превью PDF.
 */
(function () {
    'use strict';

    var fieldsList, fieldsJsonEl, htmlContent, chipsEl;
    var fields = [];  // [{key,label,type,required,defaultValue}]

    function init() {
        fieldsList = document.getElementById('fields-list');
        fieldsJsonEl = document.getElementById('fields-json');
        htmlContent = document.getElementById('html-content');
        chipsEl = document.getElementById('chips');

        // Загружаем начальные поля
        try {
            var raw = window.TPL_INITIAL_FIELDS_JSON || '[]';
            fields = JSON.parse(raw);
            if (!Array.isArray(fields)) fields = [];
        } catch (e) {
            fields = [];
        }
        // Нормализуем поле defaultValue
        fields = fields.map(function (f) {
            return {
                key: f.key || '',
                label: f.label || '',
                type: f.type || 'text',
                required: f.required === true,
                defaultValue: f.defaultValue || ''
            };
        });

        renderFields();
        renderChips();


        // --- ВИЗУАЛЬНЫЙ РЕДАКТОР ---
        var visualEditor = document.getElementById('visual-editor');
        var htmlContent = document.getElementById('html-content');
        var btnToggleHtml = document.getElementById('btn-toggle-html');
        var btnInsertField = document.getElementById('btn-insert-field');
        var docxFile = document.getElementById('docx-file');

        if (visualEditor && htmlContent) {
            // Инициализация из textarea
            visualEditor.innerHTML = htmlContent.value || '<p>Загрузите Word или начните печатать...</p>';

            // Синхронизация перед сохранением формы
            document.getElementById('tpl-form').addEventListener('submit', function () {
                htmlContent.value = visualEditor.innerHTML;
            });

            // Переключение HTML / Визуально
            btnToggleHtml.addEventListener('click', function () {
                if (htmlContent.style.display === 'none') {
                    htmlContent.style.display = 'block';
                    visualEditor.style.display = 'none';
                    btnToggleHtml.textContent = '👁 Визуально';
                } else {
                    htmlContent.style.display = 'none';
                    visualEditor.style.display = 'block';
                    btnToggleHtml.textContent = '👁 HTML код';
                    visualEditor.innerHTML = htmlContent.value;
                }
            });

            // Загрузка Word
            docxFile.addEventListener('change', async function() {
                if (!docxFile.files.length) return;
                var file = docxFile.files[0];
                var formData = new FormData();
                formData.append('file', file);
                
                visualEditor.innerHTML = '<p style="color:#6a7285;">Конвертация Word... ⏳</p>';
                
                try {
                    var resp = await fetch('/admin/templates/import-docx', {
                        method: 'POST',
                        body: formData
                    });
                    var data = await resp.json();
                    if (resp.ok && data.html) {
                        visualEditor.innerHTML = data.html;
                        htmlContent.value = data.html;
                    } else {
                        alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
                        visualEditor.innerHTML = htmlContent.value;
                    }
                } catch (e) {
                    alert('Ошибка сети: ' + e.message);
                    visualEditor.innerHTML = htmlContent.value;
                }
                docxFile.value = ''; // сброс
            });

            // Вставка плейсхолдера
            btnInsertField.addEventListener('click', function() {
                var sel = window.getSelection();
                if (!sel.rangeCount || sel.isCollapsed) {
                    alert('Сначала выделите текст в редакторе, который нужно заменить на поле.');
                    return;
                }
                var range = sel.getRangeAt(0);
                var selectedText = range.toString();
                
                var key = prompt('Введите ключ для этого поля (латиницей, без пробелов, например: fio, date, summa):\n\nВыделенный текст: "' + selectedText + '"', 'field_' + Math.floor(Math.random()*1000));
                if (!key) return;
                
                // Очистка ключа от мусора
                key = key.trim().replace(/[^a-zA-Z0-9_]/g, '');
                if (!key) return;

                // Заменяем выделенный текст на {{key}}
                var span = document.createElement('span');
                span.className = 'tpl-placeholder-chip'; // подсветим
                span.style.background = '#e6efff';
                span.style.color = '#2454ff';
                span.style.padding = '2px 4px';
                span.style.borderRadius = '4px';
                span.textContent = '{{' + key + '}}';
                
                range.deleteContents();
                range.insertNode(span);
                
                // Убираем выделение
                sel.removeAllRanges();
                
                // Сразу обновляем textarea
                htmlContent.value = visualEditor.innerHTML;
            });
        }

        document.getElementById('add-field').addEventListener('click', addField);
        document.getElementById('btn-preview').addEventListener('click', previewPdf);
        document.getElementById('btn-validate').addEventListener('click', validatePlaceholders);
        htmlContent.addEventListener('input', renderChips);

        // При submit — обновить fields_json
        document.getElementById('tpl-form').addEventListener('submit', function () {
            syncFieldsJson();
        });
    }

    // ═══════════════════════════════════════════════════════
    //  Поля
    // ═══════════════════════════════════════════════════════

    function addField() {
        fields.push({
            key: 'field_' + (fields.length + 1),
            label: 'Новое поле',
            type: 'text',
            required: false,
            defaultValue: ''
        });
        renderFields();
        renderChips();
    }

    function removeField(idx) {
        if (!confirm('Удалить поле?')) return;
        fields.splice(idx, 1);
        renderFields();
        renderChips();
    }

    function renderFields() {
        fieldsList.innerHTML = '';
        fields.forEach(function (f, i) {
            var row = document.createElement('div');
            row.className = 'tpl-field-row';

            // key
            var keyInput = document.createElement('input');
            keyInput.type = 'text';
            keyInput.value = f.key;
            keyInput.placeholder = 'key';
            keyInput.pattern = '[a-zA-Z0-9_]+';
            keyInput.title = 'Только a-z, 0-9, _';
            keyInput.addEventListener('input', function () {
                f.key = keyInput.value;
                renderChips();
            });
            row.appendChild(keyInput);

            // label
            var labelInput = document.createElement('input');
            labelInput.type = 'text';
            labelInput.value = f.label;
            labelInput.placeholder = 'Название';
            labelInput.addEventListener('input', function () { f.label = labelInput.value; });
            row.appendChild(labelInput);

            // type
            var typeSel = document.createElement('select');
            ['text', 'date', 'textarea'].forEach(function (t) {
                var o = document.createElement('option');
                o.value = t;
                o.textContent = t;
                if (f.type === t) o.selected = true;
                typeSel.appendChild(o);
            });
            typeSel.addEventListener('change', function () { f.type = typeSel.value; });
            row.appendChild(typeSel);

            // required
            var reqLabel = document.createElement('label');
            reqLabel.style.fontSize = '12px';
            reqLabel.style.display = 'flex';
            reqLabel.style.alignItems = 'center';
            reqLabel.style.gap = '4px';
            var reqCb = document.createElement('input');
            reqCb.type = 'checkbox';
            reqCb.checked = f.required;
            reqCb.addEventListener('change', function () { f.required = reqCb.checked; });
            reqLabel.appendChild(reqCb);
            reqLabel.appendChild(document.createTextNode('обяз.'));
            row.appendChild(reqLabel);

            // remove
            var rm = document.createElement('button');
            rm.type = 'button';
            rm.className = 'remove-field';
            rm.textContent = '✕';
            rm.addEventListener('click', function () { removeField(i); });
            row.appendChild(rm);

            fieldsList.appendChild(row);
        });
    }

    function syncFieldsJson() {
        // Нормализуем defaultValue
        var clean = fields.filter(function (f) { return f.key && f.key.trim(); });
        fieldsJsonEl.value = JSON.stringify(clean);
    }

    // ═══════════════════════════════════════════════════════
    //  Чипы плейсхолдеров
    // ═══════════════════════════════════════════════════════

    function renderChips() {
        chipsEl.innerHTML = '';
        var html = htmlContent.value || '';
        var used = extractPlaceholders(html);

        fields.forEach(function (f) {
            if (!f.key) return;
            var chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'tpl-placeholder-chip';
            chip.textContent = '{{' + f.key + '}}';
            chip.title = 'Вставить в текст';
            chip.addEventListener('click', function () { insertPlaceholder(f.key); });
            chipsEl.appendChild(chip);
        });

        // Предупредить о плейсхолдерах, для которых нет полей
        used.forEach(function (k) {
            if (!fields.some(function (f) { return f.key === k; })) {
                var chip = document.createElement('span');
                chip.className = 'tpl-placeholder-chip missing';
                chip.textContent = '{{' + k + '}} ?';
                chip.title = 'Нет поля с таким ключом';
                chipsEl.appendChild(chip);
            }
        });
    }

    function insertPlaceholder(key) {
        var text = htmlContent.value;
        var sel = htmlContent.selectionStart;
        var before = text.substring(0, sel);
        var after = text.substring(htmlContent.selectionEnd);
        var insert = '{{' + key + '}}';
        htmlContent.value = before + insert + after;
        htmlContent.selectionStart = htmlContent.selectionEnd = sel + insert.length;
        htmlContent.focus();
        renderChips();
    }

    function extractPlaceholders(html) {
        var m = html.match(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g) || [];
        var uniq = [];
        m.forEach(function (p) {
            var key = p.replace(/[{}]/g, '').trim();
            if (uniq.indexOf(key) < 0) uniq.push(key);
        });
        return uniq;
    }

    // ═══════════════════════════════════════════════════════
    //  Превью и валидация
    // ═══════════════════════════════════════════════════════

    async function previewPdf() {
        syncFieldsJson();
        var iframe = document.getElementById('pdf-preview');
        var form = new FormData();
        form.append('htmlContent', htmlContent.value);
        form.append('fieldsJson', fieldsJsonEl.value);

        try {
            var resp = await fetch('/admin/templates/render-preview', {
                method: 'POST',
                body: form,
                credentials: 'same-origin'
            });
            if (!resp.ok) {
                alert('Не удалось сгенерировать превью (HTTP ' + resp.status + ')');
                return;
            }
            var blob = await resp.blob();
            var url = URL.createObjectURL(blob);
            iframe.src = url;
        } catch (e) {
            alert('Ошибка превью: ' + e.message);
        }
    }

    function validatePlaceholders() {
        var html = htmlContent.value || '';
        var used = extractPlaceholders(html);
        var definedKeys = fields.map(function (f) { return f.key; });

        var missing = used.filter(function (k) { return definedKeys.indexOf(k) < 0; });
        var unused = definedKeys.filter(function (k) { return used.indexOf(k) < 0 && k; });

        var box = document.getElementById('validation-result');
        var html2 = '';

        if (missing.length === 0 && unused.length === 0) {
            html2 = '<div class="alert ok">✓ Все плейсхолдеры соответствуют полям формы.</div>';
        } else {
            html2 += '<div class="alert ' + (missing.length ? 'err' : 'info') + '">';
            html2 += '<b>Используются в HTML, но не описаны в полях:</b>';
            if (missing.length === 0) {
                html2 += ' — нет';
            } else {
                html2 += '<ul style="margin:6px 0 0 20px;">';
                missing.forEach(function (k) {
                    html2 += '<li><code>{{' + k + '}}</code></li>';
                });
                html2 += '</ul>';
                html2 += '<p class="muted small" style="margin-top:8px;">'
                    + 'Добавьте эти поля или уберите плейсхолдеры из HTML.</p>';
            }
            html2 += '</div>';

            if (unused.length > 0) {
                html2 += '<div class="alert info"><b>Поля формы, которые не используются в HTML:</b> '
                    + '<code>' + unused.map(function (k) { return '{{' + k + '}}'; }).join(', ')
                    + '</code> — они не будут показаны клиенту, если не вставить их в текст.</div>';
            }
        }
        box.innerHTML = html2;
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
