package ru.example.ukep.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "monitor_events")
public class MonitorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt = Instant.now();

    @Column(nullable = false, length = 20)
    private String status;

    /** Компонент: db, smtp, storage, keys, disk, memory, admins, alert, app */
    @Column(name = "component", length = 50)
    private String component;

    @Column(length = 500)
    private String reason;

    @Column(name = "alert_sent", nullable = false)
    private boolean alertSent = false;

    public Long getId() { return id; }
    public Instant getCheckedAt() { return checkedAt; }
    public String getStatus() { return status; }
    public String getComponent() { return component; }
    public String getReason() { return reason; }
    public boolean isAlertSent() { return alertSent; }

    public void setId(Long id) { this.id = id; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
    public void setStatus(String status) { this.status = status; }
    public void setComponent(String component) { this.component = component; }
    public void setReason(String reason) { this.reason = reason; }
    public void setAlertSent(boolean alertSent) { this.alertSent = alertSent; }
}
