/**
 * Модалка выбора сертификата для подписания.
 *
 * Использование:
 *   var cert = await CertSelector.choose(certsList);
 *   if (!cert) return;  // отменил
 *   await api.signBase64(cert.thumbprint, ...);
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'afina-cert-thumbprint';

    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function getRemembered() {
        try { return localStorage.getItem(STORAGE_KEY) || null; }
        catch (e) { return null; }
    }

    function remember(thumbprint) {
        try { localStorage.setItem(STORAGE_KEY, thumbprint); }
        catch (e) { /* ignore */ }
    }

    function forget() {
        try { localStorage.removeItem(STORAGE_KEY); }
        catch (e) { /* ignore */ }
    }

    function parseSubject(subjectInfo) {
        var out = { cn: '', o: '', t: '', inn: '', snils: '' };
        if (!subjectInfo) return out;
        var re = /([A-ZА-Я0-9._]+)=(?:"([^"]*)"|([^,]+))/gi;
        var m;
        while ((m = re.exec(subjectInfo)) !== null) {
            var key = m[1].toUpperCase();
            var val = (m[2] != null ? m[2] : m[3] || '').trim();
            if (key === 'CN') out.cn = val;
            else if (key === 'O') out.o = val;
            else if (key === 'T' || key === 'TITLE') out.t = val;
            else if (key === 'ИНН' || key === 'INN') out.inn = val;
            else if (key === 'СНИЛС' || key === 'SNILS') out.snils = val;
        }
        return out;
    }

    function fmtDate(iso) {
        if (!iso) return '—';
        try {
            var d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            var p = function (n) { return String(n).padStart(2, '0'); };
            return p(d.getDate()) + '.' + p(d.getMonth() + 1) + '.' + d.getFullYear();
        } catch (e) { return iso; }
    }

    function daysLeft(iso) {
        if (!iso) return null;
        try {
            var d = new Date(iso);
            return Math.floor((d.getTime() - Date.now()) / 86400000);
        } catch (e) { return null; }
    }

    function choose(certs) {
        if (!certs || certs.length === 0) {
            return Promise.reject(new Error('Нет доступных сертификатов'));
        }
        // ВСЕГДА показываем модалку — клиент должен видеть, каким сертификатом подписывает
        console.log('[cert-selector] found certs:', certs.length);
        certs.forEach(function (c, i) {
            console.log('[cert-selector] #' + (i + 1), {
                thumbprint: c.thumbprint,
                subject: c.subjectInfo,
                validTo: c.validPeriod && c.validPeriod.to
            });
        });
        return showModal(certs);
    }

    function showModal(certs) {
        return new Promise(function (resolve) {
            var remembered = getRemembered();

            var overlay = document.createElement('div');
            overlay.className = 'cert-selector-overlay';
            overlay.innerHTML = ''
                + '<div class="cert-selector-card">'
                +   '<div class="cert-selector-head">'
                +     '<h2>Выберите сертификат для подписания</h2>'
                +     '<p class="sub">В хранилище «Личные» найдено '
                +       certs.length + ' сертификат(ов). Выберите, каким подписывать.</p>'
                +   '</div>'
                +   '<div class="cert-selector-list" id="cert-selector-list"></div>'
                +   '<div class="cert-selector-actions">'
                +     '<button type="button" class="btn-ghost" id="cert-cancel">Отмена</button>'
                +   '</div>'
                + '</div>';
            document.body.appendChild(overlay);

            var list = overlay.querySelector('#cert-selector-list');

            certs.forEach(function (cert, idx) {
                var s = parseSubject(cert.subjectInfo);
                var days = daysLeft(cert.validPeriod && cert.validPeriod.to);
                var expired = days !== null && days < 0;
                var isRemembered = cert.thumbprint === remembered;

                var el = document.createElement('div');
                el.className = 'cert-selector-item' + (expired ? ' expired' : '');
                el.dataset.idx = idx;

                var displayName = s.cn || 'Без имени';
                var orgLine = s.o ? s.o + (s.inn ? ' · ИНН ' + s.inn : '') : '—';

                el.innerHTML = ''
                    + '<div class="cert-icon">' + (expired ? '⚠' : '🔑') + '</div>'
                    + '<div class="cert-body">'
                    +   '<div class="cert-name">' + esc(displayName)
                    +     (isRemembered ? ' <span class="cert-badge">последний</span>' : '')
                    +     (expired ? ' <span class="cert-badge-err">истёк</span>' : '')
                    +   '</div>'
                    +   '<div class="cert-org">' + esc(orgLine) + '</div>'
                    +   '<div class="cert-meta">'
                    +     'Действителен до ' + esc(fmtDate(cert.validPeriod && cert.validPeriod.to))
                    +     (days !== null && days >= 0 && days < 60
                            ? ' · <span style="color:#a86a00;">осталось ' + days + ' дн.</span>'
                            : '')
                    +     ' · ' + esc(shortThumb(cert.thumbprint))
                    +   '</div>'
                    + '</div>'
                    + '<div class="cert-check">✓</div>';

                if (!expired) {
                    el.addEventListener('click', function () {
                        remember(cert.thumbprint);
                        cleanup();
                        resolve(cert);
                    });
                }
                list.appendChild(el);
            });

            overlay.querySelector('#cert-cancel').addEventListener('click', function () {
                cleanup();
                resolve(null);
            });
            overlay.addEventListener('click', function (e) {
                if (e.target === overlay) {
                    cleanup();
                    resolve(null);
                }
            });

            function cleanup() {
                overlay.remove();
            }
        });
    }

    function shortThumb(thumb) {
        if (!thumb) return '—';
        var clean = String(thumb).replace(/[^0-9A-Fa-f]/g, '').toUpperCase();
        if (clean.length < 8) return clean;
        return clean.substring(0, 8).match(/.{2}/g).join(':') + '…';
    }

    window.CertSelector = {
        choose: choose,
        forget: forget,
        remember: remember
    };
})();
