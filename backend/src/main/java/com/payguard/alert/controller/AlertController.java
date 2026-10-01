package com.payguard.alert.controller;

import com.payguard.alert.AlertStatus;
import com.payguard.alert.dto.AlertDetailResponse;
import com.payguard.alert.dto.AlertSummaryResponse;
import com.payguard.alert.service.AlertService;
import com.payguard.decision.Decision;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ResponseEntity<Page<AlertSummaryResponse>> getAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) Decision decision) {
        Page<AlertSummaryResponse> alertPage = alertService.getAlerts(page, size, status, decision);
        return ResponseEntity.ok(alertPage);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ResponseEntity<AlertDetailResponse> getAlertById(@PathVariable UUID id) {
        AlertDetailResponse alertDetail = alertService.getAlertById(id);
        return ResponseEntity.ok(alertDetail);
    }
}
