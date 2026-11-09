package ru.example.ukep.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentTemplateService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTemplateControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DocumentTemplateService service = Mockito.mock(DocumentTemplateService.class);
        AuditService audit = Mockito.mock(AuditService.class);
        AdminTemplateController controller = new AdminTemplateController(service, audit);

        // Standalone setup — не поднимает весь Spring, работает мгновенно
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void importDocx_shouldReturnBadRequest_whenFileIsNotDocx() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46});
        mockMvc.perform(multipart("/admin/templates/import-docx").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ожидается файл формата Word (.docx)"));
    }
}
