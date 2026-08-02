package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** HTTP contract for atomic Chat-scoped Thread creation and name-based mailbox turns. */
@AutoConfigureMockMvc
class StudioHarnessThreadControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void createsBoundThreadAcceptsNameReferenceTurnsAndOmitsActiveConfiguration() throws Exception {
    JsonNode chat = createChat("thread-http");
    String chatId = chat.path("id").asText();

    try {
      MvcResult created =
          mockMvc
              .perform(post("/api/ai/chat/{chatId}/threads", chatId))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.data.threadId").isString())
              .andExpect(jsonPath("$.data.status").value("IDLE"))
              .andExpect(jsonPath("$.data.sessionId").isString())
              .andExpect(jsonPath("$.data.headEntryId").isString())
              .andExpect(jsonPath("$.data.activeAgentName").doesNotExist())
              .andExpect(jsonPath("$.data.activeEnvironmentName").doesNotExist())
              .andExpect(jsonPath("$.data.modelId").doesNotExist())
              .andExpect(jsonPath("$.data.yoloEnabled").doesNotExist())
              .andReturn();
      JsonNode thread = data(created);
      String threadId = thread.path("threadId").asText();
      String epoch = thread.path("executionEpoch").asText();
      assertEquals("0", epoch);

      MvcResult user =
          mockMvc
              .perform(
                  post("/api/ai/runtime/threads/{threadId}/messages", threadId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {
                            "content": "hello",
                            "agentName": "default-assistant",
                            "environmentName": "web-environment",
                            "yoloEnabled": true,
                            "clientMessageId": "user-1",
                            "expectedExecutionEpoch": 0
                          }
                          """))
              .andExpect(status().isAccepted())
              .andExpect(jsonPath("$.data.inputType").value("USER_MESSAGE"))
              .andExpect(jsonPath("$.data.status").value("QUEUED"))
              .andReturn();
      String userInputId = data(user).path("inputId").asText();
      assertTrue(
          data(user).path("payloadJson").asText().contains("\"agentName\":\"default-assistant\""));
      assertTrue(data(user).path("payloadJson").asText().contains("\"yoloEnabled\":true"));

      MvcResult custom =
          mockMvc
              .perform(
                  post("/api/ai/runtime/threads/{threadId}/messages/custom", threadId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                          {
                            "role": "system",
                            "content": "rules",
                            "agentName": "custom-agent",
                            "yoloEnabled": false,
                            "clientMessageId": "custom-1",
                            "expectedExecutionEpoch": 0
                          }
                          """))
              .andExpect(status().isAccepted())
              .andExpect(jsonPath("$.data.inputType").value("CUSTOM_MESSAGE"))
              .andReturn();
      assertTrue(
          data(custom).path("payloadJson").asText().contains("\"agentName\":\"custom-agent\""));

      mockMvc
          .perform(
              post("/api/ai/runtime/threads/{threadId}/messages", threadId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "content": "changed",
                        "agentName": "deleted-agent",
                        "yoloEnabled": false,
                        "clientMessageId": "user-1",
                        "expectedExecutionEpoch": 0
                      }
                      """))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.data.inputId").value(userInputId));

      mockMvc
          .perform(
              post("/api/ai/runtime/threads/{threadId}/messages/custom", threadId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "role": "assistant",
                        "content": "forbidden",
                        "agentName": "default-assistant",
                        "yoloEnabled": false,
                        "clientMessageId": "invalid",
                        "expectedExecutionEpoch": 0
                      }
                      """))
          .andExpect(status().isBadRequest());

      mockMvc
          .perform(
              post("/api/ai/runtime/threads/{threadId}/messages", threadId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "content": "stale",
                        "agentName": "default-assistant",
                        "yoloEnabled": false,
                        "clientMessageId": "stale",
                        "expectedExecutionEpoch": 99
                      }
                      """))
          .andExpect(status().isConflict());

      mockMvc
          .perform(get("/api/ai/runtime/threads/{threadId}", threadId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.sessionId").value(thread.path("sessionId").asText()))
          .andExpect(jsonPath("$.data.activeAgentName").doesNotExist())
          .andExpect(jsonPath("$.data.activeEnvironmentName").doesNotExist());

      mockMvc
          .perform(get("/api/ai/runtime/threads/{threadId}/snapshot", threadId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.inputs.length()").value(2))
          .andExpect(jsonPath("$.data.thread.activeAgentName").doesNotExist())
          .andExpect(jsonPath("$.data.thread.modelId").doesNotExist());

      mockMvc
          .perform(
              post("/api/ai/runtime/threads/{threadId}/stop", threadId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedExecutionEpoch\":0}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.executionEpoch").value("1"))
          .andExpect(jsonPath("$.data.cancelledInputs.length()").value(2));
    } finally {
      mockMvc
          .perform(delete("/api/ai/chat/{chatId}", chatId).param("expectedVersion", "0"))
          .andExpect(status().isNoContent());
    }
  }

  private JsonNode createChat(String title) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/ai/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + title + "\",\"agentName\":\"default-assistant\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return data(result);
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
