package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/**
 * {@link StudioChatController} end-to-end tests.
 *
 * <p>验证 Chat CRUD、未知 id 失败以及 Agent name 校验。Chat 不再持有 Session 成员关系。
 */
@AutoConfigureMockMvc
public class StudioChatControllerTest extends WebPostgresTestSupport {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldCreateListUpdateAndDeleteChat() throws Exception {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("web-chat");
    create.setAgentName("default-assistant");
    create.setYoloEnabled(true);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/ai/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.title").value("web-chat"))
            .andExpect(jsonPath("$.data.agentName").value("default-assistant"))
            .andExpect(jsonPath("$.data.yoloEnabled").value(true))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andReturn();

    String chatId =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .get("data")
            .get("id")
            .asText();
    assertTrue(chatId.matches("\\d+"));
    String version =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .get("data")
            .get("version")
            .asText();

    try {
      mockMvc
          .perform(get("/api/ai/chat"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[?(@.id=='" + chatId + "')]").exists());

      mockMvc
          .perform(get("/api/ai/chat/" + chatId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(chatId))
          .andExpect(jsonPath("$.data.title").value("web-chat"));

      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setTitle("web-chat-renamed");
      update.setExpectedVersion(version);
      mockMvc
          .perform(
              put("/api/ai/chat/" + chatId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(update)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.title").value("web-chat-renamed"))
          .andExpect(jsonPath("$.data.agentName").value("default-assistant"));

      // Chat↔Session membership routes no longer exist.
      mockMvc.perform(get("/api/ai/chat/" + chatId + "/sessions")).andExpect(status().isNotFound());
    } finally {
      mockMvc
          .perform(delete("/api/ai/chat/" + chatId).param("expectedVersion", "1"))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  public void shouldRejectUnknownIdsAndInvalidAgentName() throws Exception {
    mockMvc.perform(get("/api/ai/chat/999999999999")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/ai/chat/not-a-number")).andExpect(status().isBadRequest());

    ChatCreateDTO unknownAgent = new ChatCreateDTO();
    unknownAgent.setAgentName("missing-agent");
    mockMvc
        .perform(
            post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(unknownAgent)))
        .andExpect(status().isBadRequest());

    ChatUpdateDTO unknownChatUpdate = new ChatUpdateDTO();
    unknownChatUpdate.setExpectedVersion("0");
    mockMvc
        .perform(
            put("/api/ai/chat/999999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(unknownChatUpdate)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(delete("/api/ai/chat/999999999999").param("expectedVersion", "0"))
        .andExpect(status().isNotFound());
  }

  @Test
  public void shouldCreateAndPageChatThreadsByChatNameReferences() throws Exception {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("thread-picker-chat");
    create.setAgentName("default-assistant");
    MvcResult createdChat =
        mockMvc
            .perform(
                post("/api/ai/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode chat = data(createdChat);
    String chatId = chat.path("id").asText();
    String chatVersion = chat.path("version").asText();

    try {
      MvcResult scopedCreate =
          mockMvc
              .perform(post("/api/ai/chat/{chatId}/threads", chatId))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.data.threadId").isString())
              .andExpect(jsonPath("$.data.status").value("IDLE"))
              .andExpect(jsonPath("$.data.executionEpoch").value("0"))
              .andReturn();
      String scopedThreadId = data(scopedCreate).path("threadId").asText();

      MvcResult secondCreate =
          mockMvc
              .perform(post("/api/ai/chat/{chatId}/threads", chatId))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.data.threadId").isString())
              .andReturn();
      String secondThreadId = data(secondCreate).path("threadId").asText();

      mockMvc
          .perform(
              get("/api/ai/chat/{chatId}/threads", chatId)
                  .param("sort", "created")
                  .param("limit", "100"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.items").isArray())
          .andExpect(jsonPath("$.data.items[?(@.threadId=='" + scopedThreadId + "')]").exists())
          .andExpect(jsonPath("$.data.items[?(@.threadId=='" + secondThreadId + "')]").exists())
          .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

      MvcResult firstPage =
          mockMvc
              .perform(
                  get("/api/ai/chat/{chatId}/threads", chatId)
                      .param("sort", "recent")
                      .param("limit", "1"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.items").isArray())
              .andExpect(jsonPath("$.data.items.length()").value(1))
              .andExpect(jsonPath("$.data.nextCursor").isString())
              .andReturn();
      String nextCursor = data(firstPage).path("nextCursor").asText();
      mockMvc
          .perform(
              get("/api/ai/chat/{chatId}/threads", chatId)
                  .param("sort", "recent")
                  .param("cursor", nextCursor)
                  .param("limit", "1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.items.length()").value(1))
          .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

      mockMvc
          .perform(
              get("/api/ai/chat/{chatId}/threads", chatId)
                  .param("sort", "unsupported")
                  .param("limit", "20"))
          .andExpect(status().isBadRequest());
      mockMvc
          .perform(
              get("/api/ai/chat/{chatId}/threads", chatId)
                  .param("sort", "recent")
                  .param("limit", "101"))
          .andExpect(status().isBadRequest());
    } finally {
      mockMvc
          .perform(delete("/api/ai/chat/{chatId}", chatId).param("expectedVersion", chatVersion))
          .andExpect(status().isNoContent());
    }
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
  }
}
