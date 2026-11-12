package com.smartelderly.controller;

import com.smartelderly.dto.DashboardStats;
import com.smartelderly.dto.HealthDto;
import com.smartelderly.dto.PageResponse;
import com.smartelderly.dto.ReportDto;
import com.smartelderly.dto.SettingsDto;
import com.smartelderly.service.DashboardService;
import com.smartelderly.service.HealthService;
import com.smartelderly.service.IncidentService;
import com.smartelderly.service.SettingsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard statistics, reports listing, settings and health. */
@RestController
@RequestMapping("/api")
public class PortalController {
    private final DashboardService dashboard;
    private final IncidentService incidents;
    private final SettingsService settings;
    private final HealthService health;

    public PortalController(DashboardService dashboard, IncidentService incidents, SettingsService settings, HealthService health) {
        this.dashboard = dashboard;
        this.incidents = incidents;
        this.settings = settings;
        this.health = health;
    }

    @GetMapping("/dashboard/stats")
    public DashboardStats stats(@RequestParam(defaultValue = "14") int days) {
        return dashboard.stats(days);
    }

    @GetMapping("/reports")
    public PageResponse<ReportDto> reports(@RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return incidents.reports(q, page, size);
    }

    @GetMapping("/settings")
    public SettingsDto getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsDto body, @AuthenticationPrincipal Jwt jwt) {
        return settings.update(body, CurrentUser.name(jwt));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        HealthDto h = health.check();
        return ResponseEntity.status("DOWN".equals(h.status()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).body(h);
    }
}
