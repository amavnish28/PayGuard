package com.payguard.retraining;

import com.payguard.retraining.dto.RetrainAdminResponse;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/retrain")
public class RetrainController {

    private final RetrainingService retrainingService;
    private final UserRepository userRepository;

    public RetrainController(RetrainingService retrainingService, UserRepository userRepository) {
        this.retrainingService = retrainingService;
        this.userRepository = userRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RetrainAdminResponse> triggerRetraining(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID adminId = userRepository.findByUsername(authentication.getName())
                .map(User::getId)
                .orElse(null);

        try {
            RetrainAdminResponse response = retrainingService.executeRetrainWorkflow(adminId);
            return ResponseEntity.ok(response);
        } catch (RetrainingIneligibleException ex) {
            RetrainAdminResponse ineligibleResponse = new RetrainAdminResponse(
                    false,
                    null,
                    null,
                    false,
                    ex.getMessage(),
                    null
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ineligibleResponse);
        }
    }
}
