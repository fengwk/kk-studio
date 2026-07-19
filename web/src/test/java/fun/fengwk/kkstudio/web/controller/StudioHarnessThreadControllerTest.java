package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.web.WebTestApplication;

/** HTTP contract for Session/Main Thread creation and typed durable Thread mailbox commands. */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
class StudioHarnessThreadControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  /**
   * Disables asynchronous processing so the mailbox and Stop receipt assertions are deterministic.
   */
  @MockBean private ThreadKick threadKick;

  @Test
  void createsSessionsBranchesAndTypedInputsWithHttpBoundaries() throws Exception {
    JsonNode created = createSession("web-session", true);
    String sessionId = created.path("sessionId").asText();
    String threadId = created.path("mainThreadId").asText();
    assertTrue(sessionId.matches("\\d+"));
    assertTrue(threadId.matches("\\d+"));

    mockMvc
        .perform(get("/api/sessions/{id}", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(sessionId))
        .andExpect(jsonPath("$.data.mainThreadId").value(threadId));
    mockMvc
        .perform(get("/api/sessions"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data[?(@.sessionId=='" + sessionId + "')].mainThreadId").value(threadId));

    JsonNode entries = readData(get("/api/sessions/{id}/entries", sessionId));
    String headEntryId = entries.get(entries.size() - 1).path("entryId").asText();
    assertTrue(headEntryId.matches("\\d+"));
    mockMvc
        .perform(get("/api/threads/{id}", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(threadId))
        .andExpect(jsonPath("$.data.headEntryId").value(headEntryId))
        .andExpect(jsonPath("$.data.status").value("IDLE"))
        .andExpect(jsonPath("$.data.inputSequence").value(0));

    MvcResult firstMessage =
        mockMvc
            .perform(
                post("/api/threads/{id}/messages", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"hello\",\"clientMessageId\":\"message-1\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.inputId").isString())
            .andExpect(jsonPath("$.data.threadId").value(threadId))
            .andExpect(jsonPath("$.data.inputType").value("user_message"))
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andExpect(jsonPath("$.data.resolvedAt").doesNotExist())
            .andReturn();
    String inputId = data(firstMessage).path("inputId").asText();

    mockMvc
        .perform(
            post("/api/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\",\"clientMessageId\":\"message-1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputId").value(inputId));
    mockMvc
        .perform(
            post("/api/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"changed\",\"clientMessageId\":\"message-1\"}"))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            post("/api/threads/{id}/messages/custom", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"role\":\"system\",\"content\":\"context\",\"clientMessageId\":\"custom-1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("custom_message"))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));
    mockMvc
        .perform(
            post("/api/threads/{id}/messages/custom", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"role\":\"assistant\",\"content\":\"forbidden\",\"clientMessageId\":\"custom-2\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/threads/{id}/yolo", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true,\"clientMessageId\":\"yolo-1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("set_yolo"));
    mockMvc
        .perform(
            put("/api/threads/{id}/agent", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentDefinitionId\":\"1\",\"clientMessageId\":\"agent-1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("set_agent"));
    mockMvc
        .perform(
            put("/api/threads/{id}/model", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"modelId\":\"model-1\",\"variant\":\"default\",\"clientMessageId\":\"model-1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("set_model"));
    mockMvc
        .perform(
            put("/api/threads/{id}/toolset", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tools\":[\"read\"],\"clientMessageId\":\"toolset-1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("set_toolset"));

    mockMvc
        .perform(get("/api/threads/{id}/inputs", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(6))
        .andExpect(jsonPath("$.data[0].inputId").value(inputId))
        .andExpect(jsonPath("$.data[0].sequence").value(1))
        .andExpect(jsonPath("$.data[5].sequence").value(6));

    MvcResult stop =
        mockMvc
            .perform(
                post("/api/threads/{id}/stop", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"clientRequestId\":\"stop-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stopId").isString())
            .andExpect(jsonPath("$.data.cancelledInputs.length()").value(6))
            .andExpect(jsonPath("$.data.restoredMessages[0]").value("hello"))
            .andExpect(jsonPath("$.data.restoredMessages[1]").value("context"))
            .andReturn();
    String stopId = data(stop).path("stopId").asText();
    mockMvc
        .perform(
            post("/api/threads/{id}/stop", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"stop-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.stopId").value(stopId))
        .andExpect(jsonPath("$.data.restoredMessages[0]").value("hello"));

    MvcResult branch =
        mockMvc
            .perform(
                post("/api/sessions/{id}/threads", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fromEntryId\":\"" + headEntryId + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.headEntryId").value(headEntryId))
            .andExpect(jsonPath("$.data.status").value("IDLE"))
            .andReturn();
    String branchId = data(branch).path("threadId").asText();
    JsonNode threads = readData(get("/api/sessions/{id}/threads", sessionId));
    boolean branchListed = false;
    for (JsonNode thread : threads) {
      if (branchId.equals(thread.path("threadId").asText())) {
        branchListed = true;
        break;
      }
    }
    assertTrue(branchListed);

    JsonNode otherSession = createSession("other", false);
    String otherEntryId =
        readData(get("/api/sessions/{id}/entries", otherSession.path("sessionId").asText()))
            .get(0)
            .path("entryId")
            .asText();
    mockMvc
        .perform(
            post("/api/sessions/{id}/threads", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromEntryId\":\"" + otherEntryId + "\"}"))
        .andExpect(status().isConflict());

    mockMvc
        .perform(post("/api/threads").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isMethodNotAllowed());
    mockMvc
        .perform(
            post("/api/sessions/{id}/threads", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromEntryId\":\"invalid\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/sessions/{id}/threads", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromEntryId\":\"999999999999\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put("/api/threads/{id}/yolo", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"invalid-yolo\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/threads/{id}/agent", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentDefinitionId\":\"999999999999\",\"clientMessageId\":\"invalid-agent\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put("/api/threads/{id}/model", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"modelId\":\"\",\"variant\":\"default\",\"clientMessageId\":\"invalid-model\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/threads/{id}/toolset", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tools\":null,\"clientMessageId\":\"invalid-toolset\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/threads/{id}/stop", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/threads/{id}/retry", threadId)).andExpect(status().isConflict());
    mockMvc.perform(get("/api/threads/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/threads/{id}", "999999999999")).andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentDefinitionId\":\"999999999999\"}"))
        .andExpect(status().isNotFound());
  }

  private JsonNode createSession(String title, boolean yoloEnabled) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"agentDefinitionId\":\"1\",\"title\":\""
                            + title
                            + "\",\"yoloEnabled\":"
                            + yoloEnabled
                            + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.sessionId").isString())
            .andExpect(jsonPath("$.data.mainThreadId").isString())
            .andReturn();
    return data(result);
  }

  private JsonNode readData(MockHttpServletRequestBuilder request) throws Exception {
    return data(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
