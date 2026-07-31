package fun.fengwk.kkstudio.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.service.InteractionService;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** HTTP lookup retains its route while requiring the Tool-specific query parameter. */
@AutoConfigureMockMvc
class StudioInteractionControllerTest extends WebPostgresTestSupport {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private InteractionService interactionService;

  @Test
  void getsOpenInteractionByToolInvocation() throws Exception {
    InteractionDTO dto = new InteractionDTO();
    dto.setId("1");
    dto.setToolInvocationId("41");
    dto.setStatus("OPEN");
    when(interactionService.getOpenByToolInvocation("41")).thenReturn(dto);

    mockMvc
        .perform(get("/api/ai/runtime/interactions/open").param("toolInvocationId", "41"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.toolInvocationId").value("41"));
    mockMvc
        .perform(get("/api/ai/runtime/interactions/open").param("ownerId", "41"))
        .andExpect(status().isBadRequest());
  }
}
