package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatSessionAttachDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

/**
 * {@link StudioChatController} end-to-end tests.
 *
 * <p>验证 Chat CRUD、Session 成员关联/解绑、未知 id 失败、重复 attach 幂等，以及 defaultAgent 校验。
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioChatControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldCreateListUpdateAttachDetachAndDeleteChat() throws Exception {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("web-chat");
    create.setDefaultAgentId("1");

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/chats")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.title").value("web-chat"))
            .andExpect(jsonPath("$.data.defaultAgentId").value("1"))
            .andReturn();

    String chatId =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .get("data")
            .get("id")
            .asText();
    assertTrue(chatId.matches("\\d+"));

    String sessionId = null;
    try {
      mockMvc
          .perform(get("/api/chats"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data[?(@.id=='" + chatId + "')]").exists());

      mockMvc
          .perform(get("/api/chats/" + chatId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(chatId))
          .andExpect(jsonPath("$.data.title").value("web-chat"));

      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setTitle("web-chat-renamed");
      mockMvc
          .perform(
              put("/api/chats/" + chatId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(update)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.title").value("web-chat-renamed"))
          .andExpect(jsonPath("$.data.defaultAgentId").value("1"));

      HarnessSessionCreateDTO sessionCreate = new HarnessSessionCreateDTO();
      sessionCreate.setTitle("web-session");
      sessionCreate.setYoloEnabled(false);
      MvcResult sessionResult =
          mockMvc
              .perform(
                  post("/api/sessions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(sessionCreate)))
              .andExpect(status().isCreated())
              .andReturn();
      JsonNode sessionNode =
          objectMapper.readTree(sessionResult.getResponse().getContentAsString()).get("data");
      assertNotNull(sessionNode);
      sessionId = sessionNode.get("sessionId").asText();

      ChatSessionAttachDTO attach = new ChatSessionAttachDTO();
      attach.setSessionId(sessionId);
      mockMvc
          .perform(
              post("/api/chats/" + chatId + "/sessions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(attach)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.sessionId").value(sessionId));

      // Duplicate attach is idempotent and still succeeds.
      mockMvc
          .perform(
              post("/api/chats/" + chatId + "/sessions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(attach)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.sessionId").value(sessionId));

      mockMvc
          .perform(get("/api/chats/" + chatId + "/sessions"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(1))
          .andExpect(jsonPath("$.data[0].sessionId").value(sessionId));

      mockMvc
          .perform(delete("/api/chats/" + chatId + "/sessions/" + sessionId))
          .andExpect(status().isNoContent());

      mockMvc
          .perform(get("/api/chats/" + chatId + "/sessions"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(0));
    } finally {
      mockMvc.perform(delete("/api/chats/" + chatId)).andExpect(status().isNoContent());
    }
  }

  @Test
  public void shouldRejectUnknownIdsAndInvalidDefaultAgent() throws Exception {
    mockMvc.perform(get("/api/chats/999999999999")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/chats/not-a-number")).andExpect(status().isBadRequest());

    ChatCreateDTO unknownAgent = new ChatCreateDTO();
    unknownAgent.setDefaultAgentId("999999999999");
    mockMvc
        .perform(
            post("/api/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(unknownAgent)))
        .andExpect(status().isBadRequest());

    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("for-attach-404");
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/chats")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andReturn();
    String chatId =
        objectMapper
            .readTree(createResult.getResponse().getContentAsString())
            .get("data")
            .get("id")
            .asText();
    try {
      ChatSessionAttachDTO attach = new ChatSessionAttachDTO();
      attach.setSessionId("999999999999");
      mockMvc
          .perform(
              post("/api/chats/" + chatId + "/sessions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(attach)))
          .andExpect(status().isNotFound());
    } finally {
      mockMvc.perform(delete("/api/chats/" + chatId)).andExpect(status().isNoContent());
    }
  }
}
