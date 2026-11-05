/**
 * Анимация «падающего штампа» после подписания.
 *
 * Использование:
 *   StampEffect.play('Документ подписан', function() { ... });
 *
 * Показывает круглый синий штамп с текстом, «припечатывает» его,
 * рассыпает конфетти и через 1.5с вызывает callback (или reload).
 */
(function () {
    'use strict';

    function play(message, callback) {
        var overlay = document.createElement('div');
        overlay.className = 'stamp-overlay visible';
        overlay.innerHTML = '<div class="stamp">' + (message || 'ПОДПИСАНО') + '</div>';
        document.body.appendChild(overlay);

        // Конфетти
        var colors = ['#fbbf24', '#f87171', '#34d399', '#60a5fa', '#a78bfa'];
        for (var i = 0; i < 20; i++) {
            var c = document.createElement('div');
            c.className = 'confetti';
            c.style.left = '50%';
            c.style.top = '50%';
            c.style.background = colors[i % colors.length];
            c.style.setProperty('--dx', ((Math.random() - 0.5) * 600) + 'px');
            c.style.animationDelay = (Math.random() * 0.2) + 's';
            c.style.animationDuration = (1 + Math.random() * 0.6) + 's';
            overlay.appendChild(c);
        }

        setTimeout(function () {
            overlay.style.transition = 'opacity 0.4s';
            overlay.style.opacity = '0';
            setTimeout(function () {
                overlay.remove();
                if (callback) callback();
            }, 400);
        }, 1300);
    }

    window.StampEffect = { play: play };
})();
