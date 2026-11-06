package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatYoloUpdateDTO;
import fun.fengwk.kkstudio.chat.http.ChatController;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionSummaryDTO;

import java.util.List;
import java.util.UUID;

/** Chat 控制器返回直接资源，路径和分页参数交给框架绑定。 */
class ChatControllerTest {
  private ChatService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(ChatService.class);
    mvc = MockMvcBuilders.standaloneSetup(new ChatController(service)).build();
  }

  /** 验证分页默认值、传参和不带信封的资源。 */
  @Test
  void listsChatsAndSessions() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(id(1));
    ChatPageDTO page = new ChatPageDTO();
    page.setItems(List.of(chat));
    page.setNextCursor("next");
    when(service.listChats(null, 50)).thenReturn(page);
    mvc.perform(get("/api/chat/chats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(id(1)))
        .andExpect(jsonPath("$.nextCursor").value("next"));

    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(id(2));
    ChatSessionPageDTO sessions = new ChatSessionPageDTO();
    sessions.setItems(List.of(summary));
    when(service.listSessions(id(1), "cursor", 10)).thenReturn(sessions);
    mvc.perform(
            get("/api/chat/chats/{chatId}/sessions", id(1))
                .param("cursor", "cursor")
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sessionId").value(id(2)));
    when(service.getSession(id(1), id(2))).thenReturn(summary);
    mvc.perform(get("/api/chat/chats/{chatId}/sessions/{sessionId}", id(1), id(2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(id(2)));
  }

  /** 两个 PUT 使用同一 CAS 版本，但 YOLO 不属于整体替换体。 */
  @Test
  void createsReplacesUpdatesYoloAndDeletes() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(id(1));
    when(service.createChat(any(ChatCreateDTO.class))).thenReturn(chat);
    when(service.getChat(id(1))).thenReturn(chat);
    when(service.updateChat(eq(id(1)), any(ChatUpdateDTO.class))).thenReturn(chat);
    when(service.updateYolo(eq(id(1)), any(ChatYoloUpdateDTO.class))).thenReturn(chat);
    mvc.perform(
            post("/api/chat/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"one\",\"agentName\":\"assistant\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id(1)));
    mvc.perform(get("/api/chat/chats/{chatId}", id(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id(1)));
    mvc.perform(
            put("/api/chat/chats/{chatId}", id(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"two\",\"agentName\":\"assistant\","
                        + "\"environmentName\":null,\"expectedVersion\":\"1\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> replacement = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(service).updateChat(eq(id(1)), replacement.capture());
    assertEquals("two", replacement.getValue().getTitle());
    assertNull(replacement.getValue().getEnvironmentName());
    assertEquals("1", replacement.getValue().getExpectedVersion());
    mvc.perform(
            put("/api/chat/chats/{chatId}/yolo", id(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatYoloUpdateDTO> yolo = ArgumentCaptor.forClass(ChatYoloUpdateDTO.class);
    verify(service).updateYolo(eq(id(1)), yolo.capture());
    assertTrue(yolo.getValue().isYoloEnabled());
    assertEquals("2", yolo.getValue().getExpectedVersion());
    mvc.perform(delete("/api/chat/chats/{chatId}", id(1)).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());
    verify(service).deleteChat(id(1), "3");
  }

  private static String id(long value) {
    return new UUID(0, value).toString();
  }
}
