package ru.example.ukep.dto.setup;

/**
 * Ответ на POST /setup/finalize.
 * success=true → через 2s контейнер перезапустится в обычном режиме.
 */
public class SetupFinalizeResponse {
    private boolean success;
    private String message;
    private String redirectUrl;      // куда браузеру перейти после рестарта

    public SetupFinalizeResponse(boolean success, String message, String redirectUrl) {
        this.success = success;
        this.message = message;
        this.redirectUrl = redirectUrl;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getRedirectUrl() { return redirectUrl; }
}
