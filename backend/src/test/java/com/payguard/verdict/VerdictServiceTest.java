package com.payguard.verdict;

import com.payguard.alert.Alert;
import com.payguard.alert.AlertRepository;
import com.payguard.alert.AlertStatus;
import com.payguard.decision.Decision;
import com.payguard.exception.AlertNotFoundException;
import com.payguard.exception.VerdictAlreadyExistsException;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import com.payguard.verdict.dto.VerdictRequest;
import com.payguard.verdict.dto.VerdictResponse;
import com.payguard.verdict.service.VerdictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerdictServiceTest {

    @Mock
    private AnalystVerdictRepository analystVerdictRepository;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private VerdictService verdictService;

    private UUID alertId;
    private User analystUser;
    private Alert alert;

    @BeforeEach
    void setUp() {
        alertId = UUID.randomUUID();
        analystUser = new User();
        analystUser.setId(UUID.randomUUID());
        analystUser.setUsername("test_analyst");
        analystUser.setEmail("analyst@payguard.com");
        analystUser.setRole(UserRole.ANALYST);

        alert = new Alert();
        alert.setId(alertId);
        alert.setTransactionId(UUID.randomUUID());
        alert.setDecision(Decision.REVIEW);
        alert.setStatus(AlertStatus.OPEN);
        alert.setCreatedAt(OffsetDateTime.now());
        alert.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    @DisplayName("Submit verdict FRAUD successfully and update alert status to RESOLVED")
    void submitVerdictFraudSuccess() {
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "Confirmed fraudulent velocity");

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(analystVerdictRepository.existsByAlertId(alertId)).thenReturn(false);
        when(userRepository.findByUsername("test_analyst")).thenReturn(Optional.of(analystUser));
        when(analystVerdictRepository.saveAndFlush(any(AnalystVerdict.class))).thenAnswer(invocation -> {
            AnalystVerdict av = invocation.getArgument(0);
            av.setId(UUID.randomUUID());
            return av;
        });

        VerdictResponse response = verdictService.submitVerdict(alertId, request, "test_analyst");

        assertThat(response).isNotNull();
        assertThat(response.getAlertId()).isEqualTo(alertId);
        assertThat(response.getVerdict()).isEqualTo(VerdictType.FRAUD);
        assertThat(response.getComment()).isEqualTo("Confirmed fraudulent velocity");
        assertThat(response.getAnalystUsername()).isEqualTo("test_analyst");
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);

        ArgumentCaptor<AnalystVerdict> verdictCaptor = ArgumentCaptor.forClass(AnalystVerdict.class);
        verify(analystVerdictRepository).saveAndFlush(verdictCaptor.capture());
        AnalystVerdict savedVerdict = verdictCaptor.getValue();
        assertThat(savedVerdict.getAnalystId()).isEqualTo(analystUser.getId());
        assertThat(savedVerdict.getAlertId()).isEqualTo(alertId);
        assertThat(savedVerdict.getVerdict()).isEqualTo(VerdictType.FRAUD);

        verify(alertRepository).save(alert);
    }

    @Test
    @DisplayName("Submit verdict LEGITIMATE successfully and update alert status to RESOLVED")
    void submitVerdictLegitimateSuccess() {
        VerdictRequest request = new VerdictRequest(VerdictType.LEGITIMATE, "Customer confirmed purchase via OTP");

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(analystVerdictRepository.existsByAlertId(alertId)).thenReturn(false);
        when(userRepository.findByUsername("test_analyst")).thenReturn(Optional.of(analystUser));
        when(analystVerdictRepository.saveAndFlush(any(AnalystVerdict.class))).thenAnswer(invocation -> {
            AnalystVerdict av = invocation.getArgument(0);
            av.setId(UUID.randomUUID());
            return av;
        });

        VerdictResponse response = verdictService.submitVerdict(alertId, request, "test_analyst");

        assertThat(response).isNotNull();
        assertThat(response.getVerdict()).isEqualTo(VerdictType.LEGITIMATE);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        verify(alertRepository).save(alert);
    }

    @Test
    @DisplayName("Throw AlertNotFoundException when alert does not exist")
    void submitVerdictAlertNotFound() {
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "Test comment");
        when(alertRepository.findById(alertId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verdictService.submitVerdict(alertId, request, "test_analyst"))
                .isInstanceOf(AlertNotFoundException.class)
                .hasMessageContaining("Alert not found with ID: " + alertId);

        verify(analystVerdictRepository, never()).saveAndFlush(any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Throw VerdictAlreadyExistsException when verdict already exists for alert")
    void submitVerdictAlreadyExists() {
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "Duplicate verdict attempt");
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(analystVerdictRepository.existsByAlertId(alertId)).thenReturn(true);

        assertThatThrownBy(() -> verdictService.submitVerdict(alertId, request, "test_analyst"))
                .isInstanceOf(VerdictAlreadyExistsException.class)
                .hasMessage("Verdict already exists for this alert");

        verify(analystVerdictRepository, never()).saveAndFlush(any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Throw VerdictAlreadyExistsException when race condition triggers DataIntegrityViolationException")
    void submitVerdictRaceConditionDataIntegrityViolation() {
        VerdictRequest request = new VerdictRequest(VerdictType.FRAUD, "Concurrent race test");
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(analystVerdictRepository.existsByAlertId(alertId)).thenReturn(false);
        when(userRepository.findByUsername("test_analyst")).thenReturn(Optional.of(analystUser));
        when(analystVerdictRepository.saveAndFlush(any(AnalystVerdict.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> verdictService.submitVerdict(alertId, request, "test_analyst"))
                .isInstanceOf(VerdictAlreadyExistsException.class)
                .hasMessage("Verdict already exists for this alert");

        verify(alertRepository, never()).save(any());
    }
}
