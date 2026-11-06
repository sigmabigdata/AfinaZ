package ru.example.ukep.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke-тест публичной верификации.
 * Проверяет, что /verify и /api/verify/{uuid} НЕ падают с 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicVerifyFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void verifyIndex_returns200() throws Exception {
        mvc.perform(get("/verify"))
                .andExpect(status().isOk());
    }

    @Test
    void verifyDocument_notFoundUuid_not500() throws Exception {
        String randomUuid = "00000000-0000-0000-0000-000000000001";
        int status = mvc.perform(get("/verify/" + randomUuid))
                .andReturn().getResponse().getStatus();
        assertNotEquals(500, status,
                "/verify/{uuid} не должен падать с 500 (получили " + status + ")");
    }

    @Test
    void apiVerify_badUuid_returns400() throws Exception {
        mvc.perform(get("/api/verify/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiVerify_notFound_returns404() throws Exception {
        mvc.perform(get("/api/verify/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
