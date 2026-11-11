package ru.example.ukep.dto.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * Результат preflight-проверок перед finalize.
 * Возвращается из GET /setup/preflight.
 */
public class SetupPreflightDto {
    private List<Check> checks = new ArrayList<>();
    private boolean allOk;   // true если все checks.ok

    public record Check(String name, boolean ok, String message) {}

    public void addCheck(String name, boolean ok, String message) {
        checks.add(new Check(name, ok, message));
        allOk = checks.stream().allMatch(Check::ok);
    }

    public List<Check> getChecks() { return checks; }
    public void setChecks(List<Check> checks) { this.checks = checks; }
    public boolean isAllOk() { return allOk; }
    public void setAllOk(boolean allOk) { this.allOk = allOk; }
}
