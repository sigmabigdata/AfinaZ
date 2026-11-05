-- ============================================================
-- Шаблоны документов с автозаполнением
-- ============================================================
-- Админ создаёт HTML-шаблон с плейсхолдерами {{key}}.
-- Клиент заполняет поля, сервис генерирует PDF и создаёт документ.
-- ============================================================

CREATE TABLE document_templates (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    html_content TEXT NOT NULL,
    fields_json  TEXT,
    created_by   VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_dt_active_sort ON document_templates(active, sort_order, name);

COMMENT ON TABLE  document_templates IS 'Шаблоны документов с плейсхолдерами';
COMMENT ON COLUMN document_templates.html_content IS 'HTML-шаблон с {{placeholders}}';
COMMENT ON COLUMN document_templates.fields_json  IS 'JSON-массив полей формы: [{key,label,type,required,default}]';
COMMENT ON COLUMN document_templates.sort_order   IS 'Порядок отображения в списке';

-- Стартовый шаблон — "Заявление на открытие счёта"
INSERT INTO document_templates (name, description, html_content, fields_json, created_by, sort_order) VALUES (
'Заявление на открытие счёта',
'Стандартное заявление на открытие расчётного счёта в ПАО «НБД-Банк»',
'<div class="doc">
  <h1>ЗАЯВЛЕНИЕ НА ОТКРЫТИЕ РАСЧЁТНОГО СЧЁТА</h1>
  <p class="date">г. Нижний Новгород, {{date}}</p>
  <p>Я, <b>{{full_name}}</b>, паспорт серия {{passport_series}} № {{passport_number}}, выдан {{passport_issued}}, прошу открыть мне расчётный счёт в валюте <b>{{currency}}</b>.</p>
  <p>Контактный телефон: <b>{{phone}}</b></p>
  <p>Email: <b>{{email}}</b></p>
  <p>С правилами обслуживания ознакомлен(а) и согласен(на).</p>
  <br>
  <table class="sign">
    <tr><td>«____» ____________ 20___ г.</td><td>___________________ / {{full_name}} /</td></tr>
  </table>
</div>',
'[{"key":"date","label":"Дата","type":"date","required":true},{"key":"full_name","label":"ФИО полностью","type":"text","required":true},{"key":"passport_series","label":"Серия паспорта","type":"text","required":true},{"key":"passport_number","label":"Номер паспорта","type":"text","required":true},{"key":"passport_issued","label":"Кем выдан","type":"text","required":true},{"key":"currency","label":"Валюта счёта","type":"text","required":true,"default":"Российский рубль (RUB)"},{"key":"phone","label":"Телефон","type":"text","required":true},{"key":"email","label":"Email","type":"text","required":true}]',
'system', 10);
