package ru.example.ukep.dto.setup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Данные одного шага wizard'а.
 * Поля различаются по номеру шага (см. SetupService.StepField).
 * Пустые поля игнорируются при merge в draft.
 */
public class SetupStepRequest {
    private Map<String, String> data;

    public SetupStepRequest() {
        this.data = new LinkedHashMap<>();
    }

    public Map<String, String> getData() { return data; }
    public void setData(Map<String, String> data) {
        this.data = data == null ? new LinkedHashMap<>() : data;
    }
}
