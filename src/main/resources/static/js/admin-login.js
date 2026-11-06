import cadesplugin from './vendor/crypto-pro-cadesplugin.js';

console.log('[admin-login] module loaded');

const btn = document.getElementById('btn-cert-login');
const status = document.getElementById('status');
const errorBox = document.getElementById('error-box');

function setStatus(text) { status.textContent = text; }
function showError(text) {
    errorBox.textContent = text;
    errorBox.style.display = 'block';
    console.error('[admin-login] ERROR:', text);
}
function hideError() { errorBox.style.display = 'none'; }

/** Извлекает значение по ключу из X.500-строки. */
function extractValue(key, inputString) {
    if (!inputString) return null;
    const safeKey = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(safeKey + '=([^,]+)', 'i');
    const match = inputString.match(regex);
    if (match && match[1]) {
        return match[1].trim().replace(/^"|"$/g, '');
    }
    return null;
}

/** Разбирает hex-строку ASN.1 OCTET STRING со СНИЛС: 12 0b <11 ASCII-цифр>. */
function parseHexSnils(hex) {
    if (!hex) return '';
    let p = (hex.substring(0, 2) === '12') ? 4 : 0;
    let result = '';
    for (let i = p; i < hex.length; i += 2) {
        const code = parseInt(hex.substring(i, i + 2), 16);
        if (code >= 48 && code <= 57) result += String.fromCharCode(code);
    }
    return result.replace(/[^0-9]/g, '');
}

/** Извлекает СНИЛС из X.500-строки во всех известных формах. */
function extractSnils(subject) {
    if (!subject) return '';
    // 1. СНИЛС=18962570627 (наш случай)
    let v = extractValue('СНИЛС', subject);
    if (v) return v.replace(/[^0-9]/g, '');
    // 2. SNILS=...
    v = extractValue('SNILS', subject);
    if (v) return v.replace(/[^0-9]/g, '');
    // 3. OID.1.2.643.100.3=#hex
    const m = subject.match(/OID\.1\.2\.643\.100\.3=#([0-9a-fA-F]+)/);
    if (m) return parseHexSnils(m[1]);
    const m2 = subject.match(/1\.2\.643\.100\.3=#([0-9a-fA-F]+)/);
    if (m2) return parseHexSnils(m2[1]);
    return '';
}

/** Возвращает X.500-строку субъекта из объекта сертификата. */
function resolveSubject(certShort) {
    // crypto-pro-cadesplugin отдаёт поле subjectInfo
    if (certShort.subjectInfo) return certShort.subjectInfo;
    if (certShort.subject)     return certShort.subject;
    // fallback на случай других версий библиотеки
    for (const f of ['SubjectName', 'subjectName', 'subjectDN', 'Subject']) {
        if (certShort[f]) return certShort[f];
    }
    return null;
}

btn.addEventListener('click', async () => {
    hideError();
    try {
        setStatus('Ожидание плагина КриптоПро...');
        const api = await cadesplugin();

        setStatus('Поиск сертификатов...');
        const certs = await api.getCertsList();
        if (!certs || certs.length === 0) {
            showError('Не найдено ни одного сертификата в хранилище «Личные»');
            setStatus('');
            return;
        }

        // Явный выбор сертификата (CertSelector вернёт его без модалки, если один)
        const certShort = await window.CertSelector.choose(certs);
        if (!certShort) {
            setStatus('');
            return;
        }
        console.log('[admin-login] cert:', certShort);

        setStatus('Чтение данных сертификата...');
        const subjectName = resolveSubject(certShort);
        console.log('[admin-login] Subject:', subjectName);

        if (!subjectName) {
            showError('Не удалось получить Subject. Смотри консоль.');
            setStatus('');
            return;
        }

        const cn = extractValue('CN', subjectName);
        const snils = extractSnils(subjectName);
        console.log('[admin-login] CN:', cn, '| SNILS:', snils);

        if (!cn || !snils) {
            showError('Не удалось извлечь CN или СНИЛС. Subject: ' + subjectName);
            setStatus('');
            return;
        }

        // 1. Челлендж
        setStatus('Запрос челленджа...');
        const chResp = await fetch('/admin/challenge', { credentials: 'same-origin' });
        if (!chResp.ok) throw new Error('Челлендж: HTTP ' + chResp.status);
        const ch = await chResp.json();

        // 2. Подпись
        setStatus('Подписание челленджа...');
        const challengeB64 = btoa(unescape(encodeURIComponent(ch.challenge)));
        const signatureRaw = await api.signBase64(certShort.thumbprint, challengeB64);
        const signatureB64 = sanitizeBase64(signatureRaw);
        console.log('[admin-login] signature length:', signatureB64.length);

        // 3. Сервер
        setStatus('Проверка на сервере...');
        const resp = await fetch('/admin/cert-login', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                cn: cn, snils: snils,
                challenge: ch.challenge,
                signatureBase64: signatureB64
            })
        });
        const data = await resp.json().catch(() => ({}));
        if (resp.ok && data.success) {
            setStatus('Успех! Переход в админку...');
            window.location.href = data.redirect || '/admin';
        } else {
            showError(data.error || 'Не удалось войти');
            setStatus('');
        }
    } catch (e) {
        console.error('[admin-login] error', e);
        showError('Ошибка: ' + (e && e.message ? e.message : e));
        setStatus('');
    }
});

function sanitizeBase64(s) {
    if (!s) return s;
    return String(s)
        .replace(/-----BEGIN[^-]*-----/g, '')
        .replace(/-----END[^-]*-----/g, '')
        .replace(/[^A-Za-z0-9+/=]/g, '');
}
