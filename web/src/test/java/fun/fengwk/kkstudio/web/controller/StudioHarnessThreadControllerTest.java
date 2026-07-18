package fun.fengwk.kkstudio.web.controller;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.web.WebTestApplication;

/** Thread HTTP：创建/查询、mailbox 入队、Entry path 和 event journal。 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioHarnessThreadControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void listCreateYoloAndAgentEndpoints() throws Exception {
    String createBody =
        """
        {"agentDefinitionId":"1","title":"web-thread-title"}
        """;
    MvcResult create =
        mockMvc
            .perform(
                post("/api/threads").contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.threadId").isNotEmpty())
            .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
            .andReturn();
    JsonNode created =
        objectMapper.readTree(create.getResponse().getContentAsString()).path("data");
    String threadId = created.path("threadId").asText();
    String sessionId = created.path("sessionId").asText();

    mockMvc
        .perform(get("/api/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(
            jsonPath("$.data[?(@.threadId=='" + threadId + "')].sessionTitle")
                .value("web-thread-title"));

    mockMvc
        .perform(get("/api/threads/{id}", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionTitle").value("web-thread-title"));

    mockMvc
        .perform(get("/api/sessions/{id}/threads", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].threadId").value(threadId));

    String clientMessageId = "web-message-1";
    MvcResult firstMessage =
        mockMvc
            .perform(
                post("/api/threads/{id}/messages", threadId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"content\":\"hello\",\"clientMessageId\":\"" + clientMessageId + "\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.threadId").value(threadId))
            .andExpect(jsonPath("$.data.inputType").value("user_message"))
            .andExpect(jsonPath("$.data.clientMessageId").value(clientMessageId))
            .andReturn();
    String inputId =
        objectMapper
            .readTree(firstMessage.getResponse().getContentAsString())
            .path("data")
            .path("inputId")
            .asText();

    // A retried HTTP mutation reuses the durable mailbox row for the same clientMessageId.
    mockMvc
        .perform(
            post("/api/threads/{id}/messages", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\",\"clientMessageId\":\"" + clientMessageId + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputId").value(inputId));

    mockMvc
        .perform(
            put("/api/threads/{id}/yolo", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("set_yolo"))
        .andExpect(jsonPath("$.data.threadId").value(threadId));

    mockMvc
        .perform(
            put("/api/threads/{id}/agent", threadId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentDefinitionId\":\"1\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.inputType").value("set_agent"));

    mockMvc
        .perform(get("/api/threads/{id}/entries", threadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].entryType").value("agent_snapshot"))
        .andExpect(jsonPath("$.data[0].entryId").isString());

    mockMvc
        .perform(get("/api/threads/{id}/inputs", threadId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data[?(@.inputId=='" + inputId + "')].clientMessageId")
                .value(clientMessageId));

    mockMvc
        .perform(get("/api/threads/{id}/events", threadId).param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].eventType").value("thread_started"))
        .andExpect(jsonPath("$.data[0].eventId").isString());

    mockMvc.perform(get("/api/threads/{id}", "abc")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/threads/{id}/events", threadId).param("afterEventId", "abc"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/threads/{id}/events", threadId).param("afterEventId", "-1"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/threads/{id}/events", threadId).param("limit", "0"))
        .andExpect(status().isBadRequest());

    String unknownId = "9999999999";
    mockMvc.perform(get("/api/threads/{id}", unknownId)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/threads/{id}/inputs", unknownId)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/sessions/{id}/threads", unknownId)).andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/threads/{id}/messages", unknownId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\",\"clientMessageId\":\"missing-thread\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/threads/{id}/events/stream", unknownId))
        .andExpect(status().isNotFound());
  }
}
