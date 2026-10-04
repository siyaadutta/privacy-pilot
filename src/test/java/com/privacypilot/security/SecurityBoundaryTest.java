package com.privacypilot.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the auth boundary is actually enforced when tokens are configured.
 * Uses H2 (no external services) and offline LLM so it needs no infrastructure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sectest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "privacypilot.outbox.worker-enabled=false",
        "privacypilot.security.operator-token=op-secret",
        "privacypilot.security.webhook-token=wh-secret"
})
class SecurityBoundaryTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void viewingRequestsWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/requests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewingRequestsWithOperatorTokenIsAllowed() throws Exception {
        mvc.perform(get("/api/requests").header("Authorization", "Bearer op-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void approveWithWrongTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/requests/1/approve").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookTokenCannotViewRequests() throws Exception {
        // Webhook principal is scoped to inbound intake only, not the operator surface.
        mvc.perform(get("/api/requests").header("Authorization", "Bearer wh-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void webhookTokenCannotApprove() throws Exception {
        mvc.perform(post("/api/requests/1/approve").header("Authorization", "Bearer wh-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void inboundProcessRejectsUnauthenticated() throws Exception {
        mvc.perform(post("/api/emails/process")
                        .contentType("application/json")
                        .content("{\"requestId\":1,\"body\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }
}
