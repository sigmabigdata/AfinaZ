package ru.example.ukep.dto.setup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Состояние wizard'а: какой шаг заполнять, что уже есть.
 * Возвращается из GET /setup/state.
 */
public class SetupStateDto {
    private int currentStep;              // 1..6
    private int totalSteps = 6;
    private boolean complete;             // все ли шаги заполнены
    private Map<String, String> draft;    // накопленные значения
    private String domain;                // APP_DOMAIN из env (для отображения)
    private boolean tokenConfigured;      // задан ли setup-токен

    public SetupStateDto() {
        this.draft = new LinkedHashMap<>();
    }

    // геттеры/сеттеры
    public int getCurrentStep() { return currentStep; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public Map<String, String> getDraft() { return draft; }
    public void setDraft(Map<String, String> draft) { this.draft = draft; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public boolean isTokenConfigured() { return tokenConfigured; }
    public void setTokenConfigured(boolean tokenConfigured) { this.tokenConfigured = tokenConfigured; }
}
