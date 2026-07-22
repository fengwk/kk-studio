package fun.fengwk.kkstudio.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.chat.service.ChatService;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * H2-backed Chat service coverage: CRUD, membership, unknown ids, attach idempotence, and default
 * Agent validation.
 */
@SpringBootTest(classes = CoreTestApplication.class)
class ChatServiceIntegrationTest {

  @Autowired private ChatService chatService;
  @Autowired private HarnessSessionCommandService sessionCommandService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void crudListsNewestFirstAndValidatesDefaultAgent() {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("alpha");
    create.setDefaultAgentId("1");
    ChatDTO created = chatService.createChat(create);
    assertNotNull(created.getId());
    assertTrue(created.getId().matches("\\d+"));
    assertEquals("alpha", created.getTitle());
    assertEquals("1", created.getDefaultAgentId());
    assertNotNull(created.getCreateTime());
    assertNotNull(created.getUpdateTime());

    String chatId = created.getId();
    try {
      ChatDTO loaded = chatService.getChat(chatId);
      assertEquals("alpha", loaded.getTitle());
      assertEquals("1", loaded.getDefaultAgentId());

      ChatCreateDTO blankTitle = new ChatCreateDTO();
      assertThrows(IllegalArgumentException.class, () -> chatService.createChat(blankTitle));

      ChatCreateDTO unknownAgent = new ChatCreateDTO();
      unknownAgent.setTitle("orphan");
      unknownAgent.setDefaultAgentId("999999999999");
      assertThrows(IllegalArgumentException.class, () -> chatService.createChat(unknownAgent));

      ChatUpdateDTO badUpdate = new ChatUpdateDTO();
      badUpdate.setDefaultAgentId("999999999999");
      assertThrows(IllegalArgumentException.class, () -> chatService.updateChat(chatId, badUpdate));

      ChatUpdateDTO blankTitleUpdate = new ChatUpdateDTO();
      blankTitleUpdate.setTitle("   ");
      assertThrows(
          IllegalArgumentException.class, () -> chatService.updateChat(chatId, blankTitleUpdate));
      assertEquals("alpha", chatService.getChat(chatId).getTitle());

      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setTitle("beta");
      update.setDefaultAgentId("");
      ChatDTO updated = chatService.updateChat(chatId, update);
      assertEquals("beta", updated.getTitle());
      assertNull(updated.getDefaultAgentId());

      ChatCreateDTO newer = new ChatCreateDTO();
      newer.setTitle("gamma");
      ChatDTO second = chatService.createChat(newer);
      try {
        List<ChatDTO> listed = chatService.listChats();
        assertTrue(listed.size() >= 2);
        int secondIndex = indexOf(listed, second.getId());
        int firstIndex = indexOf(listed, chatId);
        assertTrue(secondIndex >= 0 && firstIndex >= 0);
        assertTrue(secondIndex < firstIndex, "newest chat must appear first");
      } finally {
        chatService.deleteChat(second.getId());
      }

      assertThrows(NoSuchElementException.class, () -> chatService.getChat("999999999999"));
      assertThrows(IllegalArgumentException.class, () -> chatService.getChat("not-a-number"));
    } finally {
      chatService.deleteChat(chatId);
      assertThrows(NoSuchElementException.class, () -> chatService.getChat(chatId));
    }
  }

  @Test
  void attachDetachMembershipIsIdempotentOnDuplicateAttach() {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("membership");
    ChatDTO chat = chatService.createChat(create);
    HarnessSessionDTO session =
        sessionCommandService.createSession(sessionCreate("1", "chat-member"));
    String chatId = chat.getId();
    String sessionId = session.getSessionId();
    try {
      LocalDateTime beforeAttach = chatService.getChat(chatId).getUpdateTime();

      HarnessSessionDTO attached = chatService.attachSession(chatId, sessionId);
      assertEquals(sessionId, attached.getSessionId());
      List<HarnessSessionDTO> members = chatService.listSessions(chatId);
      assertEquals(1, members.size());
      assertEquals(sessionId, members.get(0).getSessionId());

      ChatDTO afterAttach = chatService.getChat(chatId);
      assertFalse(afterAttach.getUpdateTime().isBefore(beforeAttach));

      // Duplicate attach is idempotent: membership stays one, no error.
      HarnessSessionDTO again = chatService.attachSession(chatId, sessionId);
      assertEquals(sessionId, again.getSessionId());
      assertEquals(1, chatService.listSessions(chatId).size());
      Integer membershipCount =
          jdbcTemplate.queryForObject(
              "select count(*) from chat_session where chat_id = ? and session_id = ?",
              Integer.class,
              Long.parseLong(chatId),
              Long.parseLong(sessionId));
      assertEquals(1, membershipCount);

      assertThrows(
          NoSuchElementException.class, () -> chatService.attachSession(chatId, "999999999999"));
      assertThrows(
          NoSuchElementException.class, () -> chatService.attachSession("999999999999", sessionId));

      LocalDateTime beforeDetach = chatService.getChat(chatId).getUpdateTime();
      chatService.detachSession(chatId, sessionId);
      assertTrue(chatService.listSessions(chatId).isEmpty());
      ChatDTO afterDetach = chatService.getChat(chatId);
      assertFalse(afterDetach.getUpdateTime().isBefore(beforeDetach));

      assertThrows(
          NoSuchElementException.class, () -> chatService.detachSession(chatId, sessionId));
    } finally {
      chatService.deleteChat(chatId);
      Integer leftover =
          jdbcTemplate.queryForObject(
              "select count(*) from chat_session where chat_id = ?",
              Integer.class,
              Long.parseLong(chatId));
      assertEquals(0, leftover);
    }
  }

  private static int indexOf(List<ChatDTO> listed, String id) {
    for (int i = 0; i < listed.size(); i++) {
      if (id.equals(listed.get(i).getId())) {
        return i;
      }
    }
    return -1;
  }

  private static HarnessSessionCreateDTO sessionCreate(String agentId, String title) {
    HarnessSessionCreateDTO create = new HarnessSessionCreateDTO();
    create.setTitle(title);
    create.setYoloEnabled(false);
    return create;
  }
}
