package ru.example.ukep.controller.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.dto.setup.SetupFinalizeResponse;
import ru.example.ukep.dto.setup.SetupPreflightDto;
import ru.example.ukep.dto.setup.SetupStateDto;
import ru.example.ukep.dto.setup.SetupStepRequest;
import ru.example.ukep.service.setup.SetupService;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/**
 * REST API мастера первичной настройки.
 *
 * <p>Активен только при {@code app.setup.mode=true}. Безопасность —
 * {@link ru.example.ukep.config.setup.SetupTokenFilter} + {@link ru.example.ukep.config.setup.SetupModeSecurityConfig}.
 * Токен передаётся один раз в URL: {@code /setup?token=XXX} → создаётся session.</p>
 */
@Controller
@RequestMapping("/setup")
@ConditionalOnProperty(name = "app.setup.mode", havingValue = "true")
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    /**
     * HTML страница wizard'а.
     *
     * <p>{@code ?step=N} — открыть конкретный шаг (иначе текущий из draft).</p>
     */
    @GetMapping({"", "/"})
    public String wizard(@RequestParam(value = "step", required = false) Integer stepParam,
                         Model model) {
        ru.example.ukep.dto.setup.SetupStateDto state = setup.getState();
        int step = stepParam != null
                ? Math.max(1, Math.min(6, stepParam))
                : state.getCurrentStep();

        model.addAttribute("step", step);
        model.addAttribute("draft", state.getDraft());
        model.addAttribute("state", state);

        return switch (step) {
            case 1 -> "setup/step1-domain";
            case 2 -> "setup/step2-smtp";
            case 3 -> "setup/step3-admin";
            case 4 -> "setup/step4-ip";
            case 5 -> "setup/step5-preflight";
            case 6 -> "setup/step6-confirm";
            default -> "setup/step1-domain";
        };
    }

    /** Текущее состояние wizard'а. */
    @GetMapping("/state")
    @ResponseBody
    public SetupStateDto state() {
        return setup.getState();
    }

    /** Сохранить шаг — форма (urlencoded). После сохранения — redirect на следующий шаг. */
    @PostMapping(value = "/step/{step}",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String saveStepForm(@PathVariable int step,
                               @RequestParam Map<String, String> params,
                               java.security.Principal principal,
                               RedirectAttributes ra) {
        if (step < 1 || step > 6) {
            ra.addFlashAttribute("error", "step должен быть 1..6");
            return "redirect:/setup?step=1";
        }
        try {
            String actor = principal != null ? principal.getName() : "setup-wizard";
            // Убираем служебные поля (на всякий случай)
            Map<String, String> data = new java.util.LinkedHashMap<>(params);
            data.remove("_csrf");
            setup.saveStep(step, data, actor);
            int next = Math.min(step + 1, 6);
            return "redirect:/setup?step=" + next;
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/setup?step=" + step;
        }
    }

    /** Сохранить шаг — JSON (для curl-тестов / CI/CD). */
    @PostMapping(value = "/step/{step}",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> saveStep(@PathVariable int step,
                                      @RequestBody SetupStepRequest req,
                                      java.security.Principal principal) {
        try {
            if (step < 1 || step > 6) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "step должен быть 1..6"));
            }
            String actor = principal != null ? principal.getName() : "setup-wizard";
            setup.saveStep(step, req.getData(), actor);
            return ResponseEntity.ok(Map.of("ok", true, "step", step,
                    "nextStep", Math.min(step + 1, 6)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Preflight проверки перед finalize. */
    @GetMapping("/preflight")
    @ResponseBody
    public SetupPreflightDto preflight() {
        SetupPreflightDto result = new SetupPreflightDto();
        SetupStateDto state = setup.getState();
        Map<String, String> draft = state.getDraft();

        // 1. Домен задан
        String domain = draft.get("domain");
        result.addCheck("Домен заполнен",
                domain != null && !domain.isBlank(),
                domain == null ? "(не задан)" : domain);

        // 2. DNS резолвится (если не IP)
        if (domain != null && !domain.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(domain);
                result.addCheck("DNS резолвится", true, addr.getHostAddress());
            } catch (Exception e) {
                result.addCheck("DNS резолвится", false,
                        "не удалось: " + e.getMessage());
            }
        } else {
            result.addCheck("DNS резолвится", true, "IP-адрес (проверка пропущена)");
        }

        // 3. Порт 443 слушает
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 443), 3000);
            result.addCheck("HTTPS (443) слушает", true, "Angie отвечает");
        } catch (Exception e) {
            result.addCheck("HTTPS (443) слушает", false, e.getMessage());
        }

        // 4. Админ CN + SNILS
        String cn = draft.get("adminCn");
        String snils = draft.get("adminSnils");
        boolean cnOk = cn != null && !cn.isBlank();
        boolean snilsOk = snils != null && snils.replaceAll("\\D", "").matches("^\\d{11}$");
        result.addCheck("Администратор (CN)", cnOk, cnOk ? cn : "(не задан)");
        result.addCheck("Администратор (СНИЛС)", snilsOk,
                snilsOk ? "***" + snils.substring(Math.max(0, snils.length() - 4)) : "(не задан)");

        // 5. SMTP
        String smtpMode = draft.getOrDefault("smtpMode", "log");
        if ("log".equals(smtpMode)) {
            result.addCheck("SMTP", true, "режим log (письма в логи)");
        } else if ("local".equals(smtpMode)) {
            String host = draft.getOrDefault("smtpHost", "localhost");
            int port = parseIntOr(draft.getOrDefault("smtpPort", "25"), 25);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                result.addCheck("SMTP local", true, host + ":" + port);
            } catch (Exception e) {
                result.addCheck("SMTP local", false,
                        host + ":" + port + " — " + e.getMessage());
            }
        } else if ("external".equals(smtpMode)) {
            String host = draft.get("smtpHost");
            int port = parseIntOr(draft.getOrDefault("smtpPort", "587"), 587);
            if (host != null && !host.isBlank()) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), 5000);
                    result.addCheck("SMTP external", true, host + ":" + port);
                } catch (Exception e) {
                    result.addCheck("SMTP external", false,
                            host + ":" + port + " — " + e.getMessage());
                }
            } else {
                result.addCheck("SMTP external", false, "не задан host");
            }
        }

        // 6. IP whitelist непустой
        String ip = draft.get("ipWhitelist");
        result.addCheck("IP-whitelist задан",
                ip != null && !ip.isBlank(), ip == null ? "(пусто)" : ip);

        return result;
    }

    /** Финализация — форма (urlencoded) → finalizing.html (автопереход). */
    @PostMapping(value = "/finalize",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String finalizeForm(java.security.Principal principal,
                               Model model,
                               RedirectAttributes ra) {
        String actor = principal != null ? principal.getName() : "setup-wizard";
        log.warn("setup: finalize requested by {} (form)", actor);

        // Запоминаем домен ДО вызова finalize (finalize может очистить draft)
        ru.example.ukep.dto.setup.SetupStateDto state = setup.getState();
        String domain = state.getDraft().getOrDefault("domain", "localhost");

        SetupFinalizeResponse response = setup.finalizeSetup(actor);
        if (!response.isSuccess()) {
            ra.addFlashAttribute("error", response.getMessage());
            return "redirect:/setup?step=6";
        }
        model.addAttribute("domain", domain);
        model.addAttribute("step", 6);
        return "setup/finalizing";
    }

    /** Финализация — JSON (для curl/CI/CD). */
    @PostMapping(value = "/finalize",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<SetupFinalizeResponse> finalizeSetup(
            java.security.Principal principal) {
        String actor = principal != null ? principal.getName() : "setup-wizard";
        log.warn("setup: finalize requested by {} (json)", actor);
        SetupFinalizeResponse response = setup.finalizeSetup(actor);
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    /** Сброс draft (не трогает данные). */
    @PostMapping("/reset")
    @ResponseBody
    public ResponseEntity<?> reset(java.security.Principal principal) {
        String actor = principal != null ? principal.getName() : "setup-wizard";
        setup.reset(actor);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Draft сброшен"));
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
