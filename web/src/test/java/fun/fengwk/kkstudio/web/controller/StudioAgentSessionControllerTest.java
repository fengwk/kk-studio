package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
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
public class StudioAgentSessionControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private StubProviderManager stubProviderManager;

  @BeforeEach
  public void resetStubProviderManager() {
    stubProviderManager.reset();
  }

  @Test
  public void shouldListSessionsByLatestUpdateTime() throws Exception {
    mockMvc
        .perform(
            post("/api/agent/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "agentName": "default-assistant",
                      "title": "First Session"
                    }
                    """))
        .andExpect(status().isCreated());

    MvcResult secondCreateResult =
        mockMvc
            .perform(
                post("/api/agent/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "agentName": "default-assistant",
                      "title": "Second Session"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();

    String secondSessionId =
        objectMapper
            .readTree(secondCreateResult.getResponse().getContentAsString())
            .path("data")
            .path("sessionId")
            .asText();

    MvcResult listResult =
        mockMvc
            .perform(get("/api/agent/sessions").param("pageNumber", "1").param("pageSize", "100"))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode results =
        objectMapper
            .readTree(listResult.getResponse().getContentAsString())
            .path("data")
            .path("results");
    int firstSessionIndex = indexOfSessionTitle(results, "First Session");
    int secondSessionIndex = indexOfSessionId(results, secondSessionId);
    assertTrue(firstSessionIndex >= 0);
    assertTrue(secondSessionIndex >= 0);
    assertTrue(secondSessionIndex < firstSessionIndex);
  }

  @Test
  public void shouldCreateAndLoadSessionResources() throws Exception {
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/agent/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "agentName": "default-assistant",
                      "title": "Bootstrap Session"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.agentName").value("default-assistant"))
            .andExpect(jsonPath("$.data.title").value("Bootstrap Session"))
            .andReturn();

    JsonNode created =
        objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
    String sessionId = created.path("sessionId").asText();
    assertTrue(sessionId.startsWith("se_"));

    mockMvc
        .perform(get("/api/agent/sessions/{sessionId}", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(sessionId))
        .andExpect(jsonPath("$.data.agentName").value("default-assistant"))
        .andExpect(jsonPath("$.data.title").value("Bootstrap Session"));

    mockMvc
        .perform(get("/api/agent/sessions/{sessionId}/events", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  public void shouldUpdateAndDeleteSession() throws Exception {
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/agent/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "agentName": "default-assistant",
                      "title": "Original Session"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();

    String sessionId =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .path("data")
            .path("sessionId")
            .asText();

    mockMvc
        .perform(
            put("/api/agent/sessions/{sessionId}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title": "Renamed Session"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(sessionId))
        .andExpect(jsonPath("$.data.title").value("Renamed Session"));

    mockMvc
        .perform(delete("/api/agent/sessions/{sessionId}", sessionId))
        .andExpect(status().isNoContent());

    MvcResult listResult =
        mockMvc
            .perform(get("/api/agent/sessions").param("pageNumber", "1").param("pageSize", "100"))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode results =
        objectMapper
            .readTree(listResult.getResponse().getContentAsString())
            .path("data")
            .path("results");
    assertEquals(-1, indexOfSessionId(results, sessionId));
  }

  @Test
  public void shouldAppendUserMessageAndRefreshSessionPointers() throws Exception {
    stubProviderManager.enqueueText("Agent reply");

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/agent/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "agentName": "default-assistant",
                      "title": "Message Session"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode session =
        objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
    String sessionId = session.path("sessionId").asText();

    MvcResult messageResult =
        mockMvc
            .perform(
                post("/api/agent/sessions/{sessionId}/messages", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "content": "Hello kk-studio"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.parentEventId").value("root"))
            .andExpect(jsonPath("$.data.eventType").value("user_message"))
            .andExpect(jsonPath("$.data.payloadJson").value("{\"content\":\"Hello kk-studio\"}"))
            .andExpect(jsonPath("$.data.runId").value(Matchers.startsWith("rn_")))
            .andReturn();

    JsonNode createdEvent =
        objectMapper.readTree(messageResult.getResponse().getContentAsString()).path("data");
    String eventId = createdEvent.path("eventId").asText();
    String runId = createdEvent.path("runId").asText();
    assertTrue(eventId.startsWith("ev_"));
    assertTrue(runId.startsWith("rn_"));

    mockMvc
        .perform(get("/api/agent/sessions/{sessionId}/events", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].eventId").value(eventId))
        .andExpect(jsonPath("$.data[0].runId").value(runId))
        .andExpect(jsonPath("$.data[0].payloadJson").value("{\"content\":\"Hello kk-studio\"}"))
        .andExpect(jsonPath("$.data[1].eventType").value("set_agent_info"))
        .andExpect(jsonPath("$.data[2].eventType").value("set_model_info"))
        .andExpect(jsonPath("$.data[3].eventType").value("assistant_start"))
        .andExpect(jsonPath("$.data[4].eventType").value("assistant_delta"))
        .andExpect(jsonPath("$.data[4].payloadJson", Matchers.containsString("Agent reply")))
        .andExpect(jsonPath("$.data[5].eventType").value("assistant_end"));
  }

  @Test
  public void shouldStreamSessionEvents() throws Exception {
    stubProviderManager.enqueueText("Stream reply");

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/agent/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "agentName": "default-assistant",
                      "title": "Stream Session"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();

    String sessionId =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .path("data")
            .path("sessionId")
            .asText();

    mockMvc
        .perform(
            post("/api/agent/sessions/{sessionId}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "content": "Hello stream"
                    }
                    """))
        .andExpect(status().isCreated());

    MvcResult streamResult =
        mockMvc
            .perform(
                get("/api/agent/sessions/{sessionId}/events/stream", sessionId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .param("idleTimeoutMillis", "50"))
            .andExpect(request().asyncStarted())
            .andReturn();

    MvcResult completedStreamResult =
        mockMvc
            .perform(asyncDispatch(streamResult))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

    String streamBody = completedStreamResult.getResponse().getContentAsString();
    assertTrue(streamBody.contains("event:session_event"));
    assertTrue(streamBody.contains("assistant_delta"));
    assertTrue(streamBody.contains("Stream reply"));
  }

  private int indexOfSessionId(JsonNode results, String sessionId) {
    for (int i = 0; i < results.size(); i++) {
      if (sessionId.equals(results.get(i).path("sessionId").asText())) {
        return i;
      }
    }
    return -1;
  }

  private int indexOfSessionTitle(JsonNode results, String title) {
    for (int i = 0; i < results.size(); i++) {
      if (title.equals(results.get(i).path("title").asText())) {
        return i;
      }
    }
    return -1;
  }
}
