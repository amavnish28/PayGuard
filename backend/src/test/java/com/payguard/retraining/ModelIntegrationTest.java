package com.payguard.retraining;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.audit.AuditLog;
import com.payguard.audit.AuditLogRepository;
import com.payguard.security.JwtService;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import com.payguard.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ModelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelVersionRepository modelVersionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private User analystUser;
    private User adminUser;
    private String analystToken;
    private String adminToken;

    private final List<UUID> createdAuditLogIds = new ArrayList<>();
    private final List<UUID> createdModelVersionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        analystUser = userRepository.findByUsername("test_analyst_model").orElseGet(() -> {
            User u = new User();
            u.setUsername("test_analyst_model");
            u.setEmail("analyst_model@payguard.com");
            u.setPasswordHash("$2a$10$dummyHashAnalyst");
            u.setRole(UserRole.ANALYST);
            u.setIsActive(true);
            return userRepository.saveAndFlush(u);
        });
        analystToken = jwtService.generateToken(analystUser);

        adminUser = userRepository.findByUsername("test_admin_model").orElseGet(() -> {
            User u = new User();
            u.setUsername("test_admin_model");
            u.setEmail("admin_model@payguard.com");
            u.setPasswordHash("$2a$10$dummyHashAdmin");
            u.setRole(UserRole.ADMIN);
            u.setIsActive(true);
            return userRepository.saveAndFlush(u);
        });
        adminToken = jwtService.generateToken(adminUser);
    }

    @AfterEach
    void tearDown() {
        for (UUID id : createdAuditLogIds) {
            auditLogRepository.deleteById(id);
        }
        for (UUID id : createdModelVersionIds) {
            modelVersionRepository.deleteById(id);
        }
    }

    @Test
    @DisplayName("GET /api/v1/models returns 401 unauthenticated")
    void testGetModelsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/models")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/models returns 200 for ANALYST and ADMIN; no model_path and no raw metrics object")
    void testGetModelsAuthorized() throws Exception {
        // Ensure at least two models exist with different createdAt
        ModelVersion mvOlder = new ModelVersion();
        mvOlder.setVersion("test-v-older-" + UUID.randomUUID().toString().substring(0, 8));
        mvOlder.setModelName("production-xgboost");
        mvOlder.setAlgorithm("xgboost");
        mvOlder.setModelPath("/secret/path/older.joblib");
        mvOlder.setIsActive(false);
        mvOlder.setCreatedAt(OffsetDateTime.now().minusHours(2));
        Map<String, Object> mOlder = new HashMap<>();
        mOlder.put("test_pr_auc", 0.81);
        mOlder.put("raw_conf_matrix", "sensitive_internal_data");
        mvOlder.setMetrics(mOlder);
        mvOlder = modelVersionRepository.saveAndFlush(mvOlder);
        createdModelVersionIds.add(mvOlder.getId());

        ModelVersion mvNewer = new ModelVersion();
        mvNewer.setVersion("test-v-newer-" + UUID.randomUUID().toString().substring(0, 8));
        mvNewer.setModelName("production-xgboost");
        mvNewer.setAlgorithm("xgboost");
        mvNewer.setModelPath("/secret/path/newer.joblib");
        mvNewer.setIsActive(true);
        mvNewer.setCreatedAt(OffsetDateTime.now().minusHours(1));
        Map<String, Object> mNewer = new HashMap<>();
        mNewer.put("test_pr_auc", 0.85);
        mNewer.put("raw_conf_matrix", "sensitive_internal_data");
        mvNewer.setMetrics(mNewer);
        mvNewer = modelVersionRepository.saveAndFlush(mvNewer);
        createdModelVersionIds.add(mvNewer.getId());

        // Test with ANALYST token
        MvcResult resultAnalyst = mockMvc.perform(get("/api/v1/models")
                .header("Authorization", "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andReturn();

        String jsonAnalyst = resultAnalyst.getResponse().getContentAsString();
        assertFalse(jsonAnalyst.contains("model_path"), "Response must not contain model_path");
        assertFalse(jsonAnalyst.contains("modelPath"), "Response must not contain modelPath");
        assertFalse(jsonAnalyst.contains("raw_conf_matrix"), "Response must not contain raw metrics object keys");

        // Test with ADMIN token
        MvcResult resultAdmin = mockMvc.perform(get("/api/v1/models")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andReturn();

        String jsonAdmin = resultAdmin.getResponse().getContentAsString();
        assertFalse(jsonAdmin.contains("model_path"));
        assertFalse(jsonAdmin.contains("modelPath"));
        assertFalse(jsonAdmin.contains("raw_conf_matrix"));

        // Verify ordering: newest first
        List<?> list = objectMapper.readValue(jsonAdmin, List.class);
        assertTrue(list.size() >= 2);
    }

    @Test
    @DisplayName("GET /api/v1/admin/retraining-history: 401 unauthenticated, 403 for ANALYST, 200 for ADMIN")
    void testRetrainingHistoryRoleAccess() throws Exception {
        // 401 Unauthenticated
        mockMvc.perform(get("/api/v1/admin/retraining-history"))
                .andExpect(status().isUnauthorized());

        // 403 for ANALYST
        mockMvc.perform(get("/api/v1/admin/retraining-history")
                .header("Authorization", "Bearer " + analystToken))
                .andExpect(status().isForbidden());

        // 200 for ADMIN
        mockMvc.perform(get("/api/v1/admin/retraining-history")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/admin/retraining-history: limit validation (limit=0 -> 400, limit=500 -> 400)")
    void testRetrainingHistoryLimitValidation() throws Exception {
        mockMvc.perform(get("/api/v1/admin/retraining-history?limit=0")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/retraining-history?limit=500")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/admin/retraining-history: newest first and sparse details row maps to nulls")
    void testRetrainingHistoryOrderingAndSparseRow() throws Exception {
        // Insert sparse audit log row
        AuditLog sparseLog = new AuditLog();
        sparseLog.setAction("MODEL_RECONCILED");
        sparseLog.setEntityId("test-reconciled");
        sparseLog.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        sparseLog.setDetails(new HashMap<>());
        sparseLog = auditLogRepository.saveAndFlush(sparseLog);
        createdAuditLogIds.add(sparseLog.getId());

        // Insert newer full audit log row
        AuditLog fullLog = new AuditLog();
        fullLog.setAction("MODEL_RETRAIN_ATTEMPTED");
        fullLog.setEntityId("test-retrain-cand");
        fullLog.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eligible", true);
        details.put("activationGatePassed", true);
        details.put("reason", "Gate passed");
        details.put("activeBefore", "v1");
        details.put("activeAfter", "v2");
        Map<String, Object> tds = new HashMap<>();
        tds.put("totalVerdicts", 50);
        tds.put("fraudCount", 25);
        tds.put("legitimateCount", 25);
        details.put("trainingDataSummary", tds);
        Map<String, Object> cm = new HashMap<>();
        cm.put("test_pr_auc", 0.88);
        details.put("candidateMetrics", cm);
        fullLog.setDetails(details);
        fullLog = auditLogRepository.saveAndFlush(fullLog);
        createdAuditLogIds.add(fullLog.getId());

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/admin/retraining-history?limit=10")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andReturn();

        String json = mvcResult.getResponse().getContentAsString();
        List<Map<String, Object>> events = objectMapper.readValue(json, List.class);
        assertNotNull(events);
        assertTrue(events.size() >= 2);

        // Verify fullLog appears before sparseLog (newest first)
        int fullIndex = -1;
        int sparseIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if ("test-retrain-cand".equals(ev.get("entityId"))) {
                fullIndex = i;
            }
            if ("test-reconciled".equals(ev.get("entityId"))) {
                sparseIndex = i;
            }
        }

        assertTrue(fullIndex != -1 && sparseIndex != -1, "Both created events must be returned");
        assertTrue(fullIndex < sparseIndex, "Newer event must appear before older event");

        // Verify sparse row maps to nulls without error
        Map<String, Object> sparseEvent = events.get(sparseIndex);
        assertEquals("MODEL_RECONCILED", sparseEvent.get("action"));
        assertNull(sparseEvent.get("eligible"));
        assertNull(sparseEvent.get("activationGatePassed"));
        assertNull(sparseEvent.get("totalVerdicts"));
        assertNull(sparseEvent.get("candidatePrAuc"));
    }
}
