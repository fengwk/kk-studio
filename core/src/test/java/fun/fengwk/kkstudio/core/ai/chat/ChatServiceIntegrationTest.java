package fun.fengwk.kkstudio.core.ai.chat;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.applyDevDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.runtime.thread.command.DatabaseTurnExecutionResolver;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailureKind;
import fun.fengwk.kkstudio.harness.runtime.model.plan.TurnExecutionResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.sql.Connection;
import java.util.List;

/** PostgreSQL-backed Chat coverage for visible name configuration, yolo, stale Agents and CAS. */
class ChatServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private AgentDefinitionService agentDefinitionService;
  @Autowired private DatabaseTurnExecutionResolver turnExecutionResolver;

  @Override
  protected void migrateDatabase(Connection conn) {
    applyDevDatabase(conn);
  }

  @Test
  void crudListsNewestFirstAndValidatesAgentName() {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("alpha");
    create.setAgentName("default-assistant");
    ChatDTO created = chatService.createChat(create);
    assertNotNull(created.getId());
    assertEquals("alpha", created.getTitle());
    assertEquals("default-assistant", created.getAgentName());
    assertEquals(false, created.isYoloEnabled());
    assertEquals("0", created.getVersion());

    String chatId = created.getId();
    try {
      ChatDTO loaded = chatService.getChat(chatId);
      assertEquals("default-assistant", loaded.getAgentName());

      ChatCreateDTO missingAgent = new ChatCreateDTO();
      missingAgent.setTitle("missing-agent");
      assertThrows(AiValidationException.class, () -> chatService.createChat(missingAgent));

      ChatCreateDTO blankAgent = new ChatCreateDTO();
      blankAgent.setTitle("blank-agent");
      blankAgent.setAgentName("  ");
      assertThrows(AiValidationException.class, () -> chatService.createChat(blankAgent));

      ChatCreateDTO unknownAgent = new ChatCreateDTO();
      unknownAgent.setTitle("orphan");
      unknownAgent.setAgentName("missing-agent");
      assertThrows(AiValidationException.class, () -> chatService.createChat(unknownAgent));

      ChatUpdateDTO badUpdate = new ChatUpdateDTO();
      assertThrows(AiValidationException.class, () -> chatService.updateChat(chatId, badUpdate));
      badUpdate.setAgentName("missing-agent");
      badUpdate.setExpectedVersion("0");
      assertThrows(AiValidationException.class, () -> chatService.updateChat(chatId, badUpdate));

      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setTitle("beta");
      update.setEnvironmentName("local");
      update.setYoloEnabled(true);
      update.setExpectedVersion("0");
      ChatDTO updated = chatService.updateChat(chatId, update);
      assertEquals("beta", updated.getTitle());
      assertEquals("local", updated.getEnvironmentName());
      assertTrue(updated.isYoloEnabled());
      assertEquals("1", updated.getVersion());

      ChatUpdateDTO stale = new ChatUpdateDTO();
      stale.setAgentName("missing-agent");
      stale.setExpectedVersion("0");
      assertThrows(AiVersionConflictException.class, () -> chatService.updateChat(chatId, stale));

      ChatCreateDTO newer = new ChatCreateDTO();
      newer.setTitle("gamma");
      newer.setAgentName("default-assistant");
      ChatDTO second = chatService.createChat(newer);
      try {
        List<ChatDTO> listed = chatService.listChats();
        assertTrue(indexOf(listed, second.getId()) < indexOf(listed, chatId));
      } finally {
        chatService.deleteChat(second.getId(), "0");
      }

      agentDefinitionService.deleteAgent("default-assistant", "0");
      ChatDTO staleAgentChat = chatService.getChat(chatId);
      assertEquals("default-assistant", staleAgentChat.getAgentName());
      assertTrue(staleAgentChat.isYoloEnabled());
      TurnExecutionResolver.Resolution resolution =
          turnExecutionResolver.resolve(new TurnSettings("default-assistant", null, false));
      TurnExecutionResolver.Resolution.Failed failure =
          assertInstanceOf(TurnExecutionResolver.Resolution.Failed.class, resolution);
      assertEquals(PlanningFailureKind.AGENT_NOT_FOUND, failure.failure().kind());

      ChatUpdateDTO staleAgentUpdate = new ChatUpdateDTO();
      staleAgentUpdate.setTitle("still editable");
      staleAgentUpdate.setEnvironmentName(null);
      staleAgentUpdate.setYoloEnabled(false);
      staleAgentUpdate.setExpectedVersion("1");
      ChatDTO updatedWithStaleAgent = chatService.updateChat(chatId, staleAgentUpdate);
      assertEquals("default-assistant", updatedWithStaleAgent.getAgentName());
      assertEquals("still editable", updatedWithStaleAgent.getTitle());
      assertEquals(false, updatedWithStaleAgent.isYoloEnabled());
    } finally {
      chatService.deleteChat(chatId, chatService.getChat(chatId).getVersion());
      assertThrows(AiResourceNotFoundException.class, () -> chatService.getChat(chatId));
    }
  }

  private static int indexOf(List<ChatDTO> listed, String id) {
    for (int i = 0; i < listed.size(); i++) {
      if (id.equals(listed.get(i).getId())) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }
}
