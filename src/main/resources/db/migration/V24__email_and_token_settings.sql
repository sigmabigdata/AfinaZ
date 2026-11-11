-- Настройки email (тема/тело/From) и TTL magic-link
-- Используются EmailService.sendLoginLink и UserService.generateLoginLink.

INSERT INTO app_settings (key, value, description) VALUES
    ('auth.token_ttl_hours', '10', 'Время действия magic-link (часы)'),
    ('email.login.subject',
     'ПАО "НБД-Банк" · Ссылка для входа',
     'Тема письма со ссылкой для входа'),
    ('email.login.body',
     E'Здравствуйте, {user_name}!\n\nДля входа в личный кабинет ПАО "НБД-Банк" перейдите по ссылке:\n{token_url}\n\nСсылка действует {expires_hours} часов.\nЕсли вы не запрашивали вход — проигнорируйте это письмо.',
     'Текст письма. Плейсхолдеры: {user_name}, {token_url}, {expires_hours}')
ON CONFLICT (key) DO NOTHING;
