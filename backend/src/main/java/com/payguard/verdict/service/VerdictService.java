package com.payguard.verdict.service;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.exception.AlertNotFoundException;
import com.payguard.exception.VerdictAlreadyExistsException;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.verdict.AnalystVerdict;
import com.payguard.verdict.AnalystVerdictRepository;
import com.payguard.verdict.dto.VerdictRequest;
import com.payguard.verdict.dto.VerdictResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class VerdictService {

    private final AnalystVerdictRepository analystVerdictRepository;
    private final AlertRepository alertRepository;
    private final UserRepository userRepository;

    public VerdictService(AnalystVerdictRepository analystVerdictRepository,
                          AlertRepository alertRepository,
                          UserRepository userRepository) {
        this.analystVerdictRepository = analystVerdictRepository;
        this.alertRepository = alertRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public VerdictResponse submitVerdict(UUID alertId, VerdictRequest request, String analystUsername) {
        // 1. Alert must exist; non-existent alert returns 404
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new AlertNotFoundException("Alert not found with ID: " + alertId));

        // 2. Only ONE verdict is allowed per alert. Service-layer check for 409
        if (analystVerdictRepository.existsByAlertId(alertId)) {
            throw new VerdictAlreadyExistsException("Verdict already exists for this alert");
        }

        // 3. Resolve analyst_id server-side from authenticated principal's username
        User analyst = userRepository.findByUsername(analystUsername)
                .orElseThrow(() -> new IllegalArgumentException("User not found with username: " + analystUsername));

        // 4. Create and persist AnalystVerdict
        AnalystVerdict verdict = new AnalystVerdict();
        verdict.setAlertId(alertId);
        verdict.setAnalystId(analyst.getId());
        verdict.setVerdict(request.getVerdict());
        verdict.setComment(request.getComment());
        verdict.setCreatedAt(OffsetDateTime.now());

        try {
            verdict = analystVerdictRepository.saveAndFlush(verdict);
        } catch (DataIntegrityViolationException ex) {
            // Race condition fallback
            throw new VerdictAlreadyExistsException("Verdict already exists for this alert");
        }

        // 5. Alert status is always updated to RESOLVED on verdict submission
        alert.setStatus(AlertStatus.RESOLVED);
        alert.setUpdatedAt(OffsetDateTime.now());
        alertRepository.save(alert);

        // 6. Return response with analyst username (not raw UUID)
        return new VerdictResponse(
                verdict.getId(),
                verdict.getAlertId(),
                verdict.getVerdict(),
                verdict.getComment(),
                analyst.getUsername(),
                verdict.getCreatedAt()
        );
    }
}
