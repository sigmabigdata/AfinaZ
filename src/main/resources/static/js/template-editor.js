/**
 * Редактор шаблонов документов.
 */
(function () {
    'use strict';

    var fieldsList, fieldsJsonEl, htmlContent, chipsEl;
    var fields = [];

    function init() {
        fieldsList = document.getElementById('fields-list');
        fieldsJsonEl = document.getElementById('fields-json');
        htmlContent = document.getElementById('html-content');
        chipsEl = document.getElementById('chips');

        try {
            var raw = window.TPL_INITIAL_FIELDS_JSON || '[]';
            fields = JSON.parse(raw);
            if (!Array.isArray(fields)) fields = [];
        } catch (e) {
            fields = [];
        }
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

        var addFieldBtn = document.getElementById('add-field');
        if (addFieldBtn) addFieldBtn.addEventListener('click', addField);
        
        var previewBtn = document.getElementById('btn-preview');
        if (previewBtn) previewBtn.addEventListener('click', previewPdf);
        
        var validateBtn = document.getElementById('btn-validate');
        if (validateBtn) validateBtn.addEventListener('click', validatePlaceholders);
        
        if (htmlContent) htmlContent.addEventListener('input', renderChips);

        var form = document.getElementById('tpl-form');
        if (form) {
            form.addEventListener('submit', function () {
                syncFieldsJson();
                var vis = document.getElementById('visual-editor');
                if (vis) {
                    htmlContent.value = vis.innerHTML;
                }
            });
        }

        initVisualEditor();
    }

    function initVisualEditor() {
        var visualEditor = document.getElementById('visual-editor');
        var btnToggleHtml = document.getElementById('btn-toggle-html');
        var btnInsertField = document.getElementById('btn-insert-field');
        var docxFile = document.getElementById('docx-file');
        var btnUploadDocx = document.getElementById('btn-upload-docx');

        if (!visualEditor || !htmlContent) {
            console.error("Visual editor elements missing!");
            return;
        }

        visualEditor.innerHTML = htmlContent.value || '<p style="color:#6a7285;">Загрузите Word или начните печатать...</p>';

        if (btnToggleHtml) {
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
        }

        if (docxFile) {
            docxFile.addEventListener('change', async function() {
                if (!docxFile.files.length) return;
                var file = docxFile.files[0];
                var formData = new FormData();
                formData.append('file', file);
                
                var originalText = btnUploadDocx ? btnUploadDocx.textContent : '📄 Загрузить Word';
                if (btnUploadDocx) {
                    btnUploadDocx.textContent = '⏳ Конвертация...';
                    btnUploadDocx.disabled = true;
                }
                visualEditor.innerHTML = '<p style="color:#2454ff; font-weight:bold;">Идет конвертация документа, подождите...</p>';
                
                try {
                    var resp = await fetch('/admin/templates/import-docx', {
                        method: 'POST',
                        body: formData,
                        credentials: 'same-origin'
                    });
                    
                    if (!resp.ok) {
                        var errData = await resp.text();
                        throw new Error('HTTP ' + resp.status + ': ' + errData);
                    }
                    
                    var data = await resp.json();
                    if (data.html) {
                        visualEditor.innerHTML = data.html;
                        htmlContent.value = data.html;
                    } else {
                        throw new Error('Сервер не вернул HTML');
                    }
                } catch (e) {
                    alert('Ошибка загрузки Word: ' + e.message);
                    visualEditor.innerHTML = htmlContent.value;
                } finally {
                    if (btnUploadDocx) {
                        btnUploadDocx.textContent = originalText;
                        btnUploadDocx.disabled = false;
                    }
                    docxFile.value = '';
                }
            });
        }

        if (btnInsertField) {
            btnInsertField.addEventListener('click', function() {
                var sel = window.getSelection();
                if (!sel.rangeCount || sel.isCollapsed) {
                    alert('Сначала выделите текст мышкой, который нужно заменить.');
                    return;
                }
                var range = sel.getRangeAt(0);
                var selectedText = range.toString();
                
                var key = prompt('Введите ключ для поля (латиницей, например: fio, date):', 'field_' + Math.floor(Math.random()*1000));
                if (!key) return;
                key = key.trim().replace(/[^a-zA-Z0-9_]/g, '');
                if (!key) return;

                var span = document.createElement('span');
                span.className = 'tpl-placeholder-chip';
                span.style.background = '#e6efff';
                span.style.color = '#2454ff';
                span.style.padding = '2px 4px';
                span.style.borderRadius = '4px';
                span.textContent = '{{' + key + '}}';
                
                range.deleteContents();
                range.insertNode(span);
                sel.removeAllRanges();
                htmlContent.value = visualEditor.innerHTML;
            });
        }
    }

    function addField() {
        fields.push({ key: 'field_' + (fields.length + 1), label: 'Новое поле', type: 'text', required: false, defaultValue: '' });
        renderFields();
        renderChips();
    }

    function renderFields() {
        if (!fieldsList) return;
        fieldsList.innerHTML = '';
        fields.forEach(function (f, i) {
            var row = document.createElement('div');
            row.className = 'tpl-field-row';
            row.innerHTML = `
                <input type="text" value="${f.key}" placeholder="ключ (англ)" style="border-color: #cbd5e1;" oninput="window.__updateField(${i}, 'key', this.value)" title="Только английские буквы, цифры и _">
                <input type="text" value="${f.label}" placeholder="Название (рус)" oninput="window.__updateField(${i}, 'label', this.value)">
                <select onchange="window.__updateField(${i}, 'type', this.value)">
                    <option value="text" ${f.type==='text'?'selected':''}>text</option>
                    <option value="date" ${f.type==='date'?'selected':''}>date</option>
                    <option value="textarea" ${f.type==='textarea'?'selected':''}>textarea</option>
                </select>
                <label style="font-size:12px;display:flex;align-items:center;gap:4px;">
                    <input type="checkbox" ${f.required?'checked':''} onchange="window.__updateField(${i}, 'required', this.checked)"> обяз.
                </label>
                <button type="button" class="remove-field" onclick="window.__removeField(${i})">✕</button>
            `;
            fieldsList.appendChild(row);
        });
    }

    window.__updateField = function(idx, prop, val) {
        if (prop === 'key') {
            // Автоматически заменяем русские буквы на английские (транслит) и убираем пробелы
            var map = {'а':'a','б':'b','в':'v','г':'g','д':'d','е':'e','ё':'e','ж':'zh','з':'z','и':'i','й':'y','к':'k','л':'l','м':'m','н':'n','о':'o','п':'p','р':'r','с':'s','т':'t','у':'u','ф':'f','х':'h','ц':'ts','ч':'ch','ш':'sh','щ':'sch','ъ':'','ы':'y','ь':'','э':'e','ю':'yu','я':'ya','А':'A','Б':'B','В':'V','Г':'G','Д':'D','Е':'E','Ё':'E','Ж':'Zh','З':'Z','И':'I','Й':'Y','К':'K','Л':'L','М':'M','Н':'N','О':'O','П':'P','Р':'R','С':'S','Т':'T','У':'U','Ф':'F','Х':'H','Ц':'Ts','Ч':'Ch','Ш':'Sh','Щ':'Sch','Ъ':'','Ы':'Y','Ь':'','Э':'E','Ю':'Yu','Я':'Ya'};
            var cleaned = val.split('').map(function(ch) { return map[ch] || ch; }).join('');
            cleaned = cleaned.replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
            fields[idx][prop] = cleaned;
            // Обновляем поле в интерфейсе
            var input = document.querySelectorAll('.tpl-field-row')[idx].querySelector('input');
            if (input) input.value = cleaned;
        } else {
            fields[idx][prop] = val;
        }
        if (prop === 'key') renderChips();
    };
    window.__removeField = function(idx) {
        if (!confirm('Удалить поле?')) return;
        fields.splice(idx, 1);
        renderFields();
        renderChips();
    };

    function syncFieldsJson() {
        var clean = fields.filter(function (f) { return f.key && f.key.trim(); });
        if (fieldsJsonEl) fieldsJsonEl.value = JSON.stringify(clean);
    }

    function renderChips() {
        if (!chipsEl) return;
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
            chip.onclick = function () { insertPlaceholder(f.key); };
            chipsEl.appendChild(chip);
        });

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
        
        var vis = document.getElementById('visual-editor');
        if (vis) vis.innerHTML = htmlContent.value;
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

    async function previewPdf() {
        syncFieldsJson();
        var iframe = document.getElementById('pdf-preview');
        var form = new FormData();
        
        // ФИКС: Синхронизируем текст из визуального редактора в скрытое поле
        var vis = document.getElementById('visual-editor');
        if (vis && htmlContent.style.display === 'none') {
            htmlContent.value = vis.innerHTML;
        }

        form.append('htmlContent', htmlContent.value);
        form.append('fieldsJson', fieldsJsonEl.value);

        try {
            var resp = await fetch('/admin/templates/render-preview', {
                method: 'POST',
                body: form,
                credentials: 'same-origin'
            });
            if (!resp.ok) { alert('Ошибка превью: HTTP ' + resp.status); return; }
            var blob = await resp.blob();
            iframe.src = URL.createObjectURL(blob);
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
        var out = '';
        if (missing.length === 0 && unused.length === 0) {
            out = '<div class="alert ok">✓ Все плейсхолдеры соответствуют полям формы.</div>';
        } else {
            if (missing.length > 0) {
                out += '<div class="alert err"><b>Используются в HTML, но не описаны в полях:</b><ul>';
                missing.forEach(function (k) { out += '<li><code>{{' + k + '}}</code></li>'; });
                out += '</ul></div>';
            }
            if (unused.length > 0) {
                out += '<div class="alert info"><b>Поля формы, которые не используются в HTML:</b> ';
                out += unused.map(function (k) { return '{{' + k + '}}'; }).join(', ') + '</div>';
            }
        }
        box.innerHTML = out;
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
