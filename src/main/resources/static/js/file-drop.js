/**
 * Превращает <input type="file"> в стилизованную drop-zone
 * с поддержкой drag & drop.
 *
 * Автоматически применяется ко всем input[type=file] на странице,
 * у которых нет атрибута data-no-drop.
 *
 * Если input лежит в <form enctype="multipart/form-data"> — работает
 * как обычно (форма отправится при submit).
 *
 * Если input лежит вне form и имеет data-auto-upload — при выборе
 * файла форма (найденная по data-form-id) отправится автоматически.
 */
(function () {
    'use strict';

    function init() {
        document.querySelectorAll('input[type=file]').forEach(setup);
    }

    function setup(input) {
        if (input.dataset.dropZoneReady === '1') return;
        if (input.dataset.noDrop === '1') return;
        input.dataset.dropZoneReady = '1';

        // Найти или создать контейнер
        var zone = document.createElement('div');
        zone.className = 'file-drop-zone';
        zone.innerHTML = ''
            + '<span class="icon">📎</span>'
            + '<span class="label">Перетащите файл сюда или кликните</span>';

        // Переместить input внутрь зоны
        input.parentNode.insertBefore(zone, input);
        zone.appendChild(input);

        var label = zone.querySelector('.label');
        var icon = zone.querySelector('.icon');

        function updateLabel() {
            if (input.files && input.files.length) {
                var name = input.files[0].name;
                label.textContent = name;
                zone.classList.add('has-file');
                icon.textContent = '📄';
            } else {
                label.textContent = 'Перетащите файл сюда или кликните';
                zone.classList.remove('has-file');
                icon.textContent = '📎';
            }
        }

        function handleFiles(files) {
            if (!files || !files.length) return;
            // присваиваем через DataTransfer
            try {
                var dt = new DataTransfer();
                for (var i = 0; i < files.length; i++) dt.items.add(files[i]);
                input.files = dt.files;
            } catch (e) {
                console.warn('[file-drop] DataTransfer failed:', e);
                return;
            }
            updateLabel();
            // Событие change (для авто-upload)
            input.dispatchEvent(new Event('change', { bubbles: true }));
        }

        // Клик по зоне = клик по input
        zone.addEventListener('click', function (e) {
            if (e.target !== input) {
                e.preventDefault();
                input.click();
            }
        });

        // Drag & drop
        zone.addEventListener('dragover', function (e) {
            e.preventDefault();
            e.stopPropagation();
            zone.classList.add('dragover');
        });
        zone.addEventListener('dragleave', function (e) {
            e.preventDefault();
            zone.classList.remove('dragover');
        });
        zone.addEventListener('drop', function (e) {
            e.preventDefault();
            e.stopPropagation();
            zone.classList.remove('dragover');
            if (e.dataTransfer && e.dataTransfer.files) {
                handleFiles(e.dataTransfer.files);
            }
        });

        input.addEventListener('change', updateLabel);
        updateLabel();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    window.FileDrop = { init: init };
})();
