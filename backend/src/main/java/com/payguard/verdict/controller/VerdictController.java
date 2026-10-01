package com.payguard.verdict.controller;

import com.payguard.verdict.dto.VerdictRequest;
import com.payguard.verdict.dto.VerdictResponse;
import com.payguard.verdict.service.VerdictService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts/{alertId}/verdict")
public class VerdictController {

    private final VerdictService verdictService;

    public VerdictController(VerdictService verdictService) {
        this.verdictService = verdictService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ResponseEntity<VerdictResponse> submitVerdict(
            @PathVariable UUID alertId,
            @Valid @RequestBody VerdictRequest request,
            Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        VerdictResponse response = verdictService.submitVerdict(alertId, request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
