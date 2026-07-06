package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.web.WebTestApplication;
import fun.fengwk.kkstudio.web.testing.StubProviderManager;

/**
 * @author fengwk
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioAgentRunControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private StubProviderManager stubProviderManager;

  @BeforeEach
  public void resetStubProviderManager() {
    stubProviderManager.reset();
  }

  @Test
  public void shouldListQueuedRunsForSession() throws Exception {
    stubProviderManager.enqueueText("Agent reply");

    MvcResult createSessionResult =
        mockMvc
            .perform(
                post("/api/agent/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "agentName": "default-assistant",
                      "title": "Run Session"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode session =
        objectMapper.readTree(createSessionResult.getResponse().getContentAsString()).path("data");
    String sessionId = session.path("sessionId").asText();

    MvcResult createMessageResult =
        mockMvc
            .perform(
                post("/api/agent/sessions/{sessionId}/messages", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "content": "Generate a queued run"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode createdEvent =
        objectMapper.readTree(createMessageResult.getResponse().getContentAsString()).path("data");
    String eventId = createdEvent.path("eventId").asText();
    String runId = createdEvent.path("runId").asText();
    assertTrue(runId.startsWith("rn_"));

    mockMvc
        .perform(get("/api/agent/sessions/{sessionId}/runs", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].runId").value(runId))
        .andExpect(jsonPath("$.data[0].sessionId").value(sessionId))
        .andExpect(jsonPath("$.data[0].triggerEventId").value(eventId))
        .andExpect(jsonPath("$.data[0].status").value("succeeded"));
  }
}
