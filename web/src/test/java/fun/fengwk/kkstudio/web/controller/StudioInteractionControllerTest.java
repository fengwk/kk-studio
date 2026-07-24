package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.core.harness.interaction.service.InteractionService;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

/** HTTP coverage for generic Interaction lookup, owner lookup, response, and conflict semantics. */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
class StudioInteractionControllerTest {
  private static final String LARGE_ID = "9007199254740993";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private InteractionService interactionService;

  /** Id/version remain decimal strings and the response command reaches the generic service. */
  @Test
  void queriesAndRespondsWithStringIdsAndRawJson() throws Exception {
    InteractionDTO dto = dto();
    when(interactionService.get(LARGE_ID)).thenReturn(dto);
    when(interactionService.getOpenByOwner("THREAD", LARGE_ID)).thenReturn(dto);
    when(interactionService.respond(eq(LARGE_ID), any())).thenReturn(dto);

    mockMvc
        .perform(get("/api/interactions/{id}", LARGE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(LARGE_ID))
        .andExpect(jsonPath("$.data.version").value("7"));
    mockMvc
        .perform(
            get("/api/interactions/open").param("ownerKind", "THREAD").param("ownerId", LARGE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ownerId").value(LARGE_ID));
    mockMvc
        .perform(
            post("/api/interactions/{id}/response", LARGE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"7\",\"responseJson\":\"{\\\"yes\\\":true}\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.projectionJson").value("{\"question\":true}"));
  }

  /** Missing generic facts map to 404 while stale/duplicate response conflicts map to 409. */
  @Test
  void mapsMissingAndDuplicateResponsesPrecisely() throws Exception {
    when(interactionService.get(LARGE_ID))
        .thenThrow(new IllegalArgumentException("unknown interaction: " + LARGE_ID));
    when(interactionService.respond(eq(LARGE_ID), any()))
        .thenThrow(new IllegalStateException("interaction is not open"));

    mockMvc.perform(get("/api/interactions/{id}", LARGE_ID)).andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/interactions/{id}/response", LARGE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"7\",\"responseJson\":\"true\"}"))
        .andExpect(status().isConflict());
  }

  private static InteractionDTO dto() {
    InteractionDTO dto = new InteractionDTO();
    dto.setId(LARGE_ID);
    dto.setOwnerKind("THREAD");
    dto.setOwnerId(LARGE_ID);
    dto.setHandlerType("confirm");
    dto.setProjectionJson("{\"question\":true}");
    dto.setStatus("OPEN");
    dto.setVersion("7");
    return dto;
  }
}
