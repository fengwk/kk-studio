package fun.fengwk.kkstudio.web.chat;

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

import fun.fengwk.kkstudio.chat.ChatService;
import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatYoloUpdateDTO;
import fun.fengwk.kkstudio.chat.http.ChatController;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.util.List;
import java.util.UUID;

/** Chat 控制器的 HTTP 契约：直接资源、UUID 路径、整体替换与独立 YOLO 子资源。 */
class ChatControllerTest {

  private ChatService chatService;
  private MockMvc mockMvc;

  private static String idText(long value) {
    return new UUID(0L, value).toString();
  }

  @BeforeEach
  void setUp() {
    chatService = mock(ChatService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ChatController(chatService))
            .setControllerAdvice(new StudioProblemAdvice(new StudioMessageService()))
            .build();
  }

  /** 验证分页资源、Session 摘要与单条读取都不带信封。 */
  @Test
  void listsChatsAndSessions() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(idText(1));
    chat.setTitle("chat-1");
    ChatPageDTO page = new ChatPageDTO();
    page.setItems(List.of(chat));
    page.setNextCursor("cursor-2");
    when(chatService.listChats(null, 50)).thenReturn(page);

    mockMvc
        .perform(get("/api/chat/chats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(idText(1)))
        .andExpect(jsonPath("$.items[0].title").value("chat-1"))
        .andExpect(jsonPath("$.nextCursor").value("cursor-2"));
    verify(chatService).listChats(null, 50);

    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(idText(2));
    summary.setName("session name");
    summary.setFirstMessagePreview("hello");
    summary.setThreadCount(2);
    ChatSessionPageDTO sessions = new ChatSessionPageDTO();
    sessions.setItems(List.of(summary));
    sessions.setNextCursor("next");
    when(chatService.listSessions(idText(7), "cursor-1", 10)).thenReturn(sessions);

    mockMvc
        .perform(
            get("/api/chat/chats/{chatId}/sessions", idText(7))
                .param("cursor", "cursor-1")
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sessionId").value(idText(2)))
        .andExpect(jsonPath("$.items[0].name").value("session name"))
        .andExpect(jsonPath("$.items[0].firstMessagePreview").value("hello"))
        .andExpect(jsonPath("$.items[0].threadCount").value(2))
        .andExpect(jsonPath("$.nextCursor").value("next"));
    verify(chatService).listSessions(idText(7), "cursor-1", 10);

    when(chatService.getSession(idText(7), idText(2))).thenReturn(summary);
    mockMvc
        .perform(get("/api/chat/chats/{chatId}/sessions/{sessionId}", idText(7), idText(2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(idText(2)))
        .andExpect(jsonPath("$.name").value("session name"));
    verify(chatService).getSession(idText(7), idText(2));
  }

  /** 领域错误交给统一 advice：缺失 Chat 输出 not_found problem，控制器不再自行翻译。 */
  @Test
  void getChatTranslatesDomainNotFound() throws Exception {
    when(chatService.getChat(idText(5))).thenThrow(DomainException.notFound("chat"));

    mockMvc
        .perform(get("/api/chat/chats/{chatId}", idText(5)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.detail").value("chat not found"));
    verify(chatService).getChat(idText(5));
  }

  /** 创建 201；PUT 整体替换 title/agentName/environmentName；YOLO 独立提交；DELETE 必带版本。 */
  @Test
  void createsReplacesUpdatesYoloAndDeletes() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(idText(1));
    when(chatService.createChat(any(ChatCreateDTO.class))).thenReturn(chat);
    when(chatService.getChat(idText(1))).thenReturn(chat);
    when(chatService.updateChat(eq(idText(1)), any(ChatUpdateDTO.class))).thenReturn(chat);
    when(chatService.updateYolo(eq(idText(1)), any(ChatYoloUpdateDTO.class))).thenReturn(chat);

    mockMvc
        .perform(
            post("/api/chat/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"one\",\"agentName\":\"assistant\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(idText(1)));
    ArgumentCaptor<ChatCreateDTO> created = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(created.capture());
    assertEquals("one", created.getValue().getTitle());

    mockMvc
        .perform(get("/api/chat/chats/{chatId}", idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(idText(1)));

    mockMvc
        .perform(
            put("/api/chat/chats/{chatId}", idText(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"two\",\"agentName\":\"assistant\","
                        + "\"environmentName\":null,\"expectedVersion\":\"1\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> replacement = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq(idText(1)), replacement.capture());
    assertEquals("two", replacement.getValue().getTitle());
    assertEquals("assistant", replacement.getValue().getAgentName());
    assertNull(replacement.getValue().getEnvironmentName());
    assertEquals("1", replacement.getValue().getExpectedVersion());

    mockMvc
        .perform(
            put("/api/chat/chats/{chatId}/yolo", idText(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatYoloUpdateDTO> yolo = ArgumentCaptor.forClass(ChatYoloUpdateDTO.class);
    verify(chatService).updateYolo(eq(idText(1)), yolo.capture());
    assertTrue(yolo.getValue().isYoloEnabled());
    assertEquals("2", yolo.getValue().getExpectedVersion());

    mockMvc
        .perform(delete("/api/chat/chats/{chatId}", idText(1)).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());
    verify(chatService).deleteChat(idText(1), "3");

    mockMvc
        .perform(delete("/api/chat/chats/{chatId}", idText(1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }
}
