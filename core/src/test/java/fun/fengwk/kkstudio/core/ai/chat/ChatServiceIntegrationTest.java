package fun.fengwk.kkstudio.core.ai.chat;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.applyDevDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.sql.Connection;
import java.util.List;

/** PostgreSQL-backed Chat service coverage: CRUD, unknown ids, and default Agent validation. */
class ChatServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private ChatService chatService;

  @Override
  protected void migrateDatabase(Connection conn) {
    applyDevDatabase(conn);
  }

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
    assertEquals("0", created.getVersion());
    assertNotNull(created.getCreateTime());
    assertNotNull(created.getUpdateTime());

    String chatId = created.getId();
    try {
      ChatDTO loaded = chatService.getChat(chatId);
      assertEquals("alpha", loaded.getTitle());
      assertEquals("1", loaded.getDefaultAgentId());

      ChatCreateDTO blankTitle = new ChatCreateDTO();
      assertThrows(AiValidationException.class, () -> chatService.createChat(blankTitle));

      ChatCreateDTO unknownAgent = new ChatCreateDTO();
      unknownAgent.setTitle("orphan");
      unknownAgent.setDefaultAgentId("999999999999");
      assertThrows(AiValidationException.class, () -> chatService.createChat(unknownAgent));

      ChatUpdateDTO badUpdate = new ChatUpdateDTO();
      assertThrows(AiValidationException.class, () -> chatService.updateChat(chatId, badUpdate));
      badUpdate.setDefaultAgentId("999999999999");
      badUpdate.setExpectedVersion("0");
      assertThrows(AiValidationException.class, () -> chatService.updateChat(chatId, badUpdate));

      ChatUpdateDTO blankTitleUpdate = new ChatUpdateDTO();
      blankTitleUpdate.setTitle("   ");
      blankTitleUpdate.setExpectedVersion("0");
      assertThrows(
          AiValidationException.class, () -> chatService.updateChat(chatId, blankTitleUpdate));
      assertEquals("alpha", chatService.getChat(chatId).getTitle());

      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setTitle("beta");
      update.setDefaultAgentId("");
      update.setExpectedVersion("0");
      ChatDTO updated = chatService.updateChat(chatId, update);
      assertEquals("beta", updated.getTitle());
      assertNull(updated.getDefaultAgentId());
      assertEquals("1", updated.getVersion());

      ChatUpdateDTO stale = new ChatUpdateDTO();
      stale.setExpectedVersion("0");
      stale.setDefaultAgentId("999999999999");
      assertThrows(AiVersionConflictException.class, () -> chatService.updateChat(chatId, stale));

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
        chatService.deleteChat(second.getId(), "0");
      }

      assertThrows(AiResourceNotFoundException.class, () -> chatService.getChat("999999999999"));
      assertThrows(AiValidationException.class, () -> chatService.getChat("not-a-number"));
    } finally {
      chatService.deleteChat(chatId, "1");
      assertThrows(AiResourceNotFoundException.class, () -> chatService.getChat(chatId));
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
}
