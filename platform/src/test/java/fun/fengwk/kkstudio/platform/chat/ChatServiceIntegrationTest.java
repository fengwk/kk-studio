package fun.fengwk.kkstudio.platform.chat;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.applyDevDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** PostgreSQL 后端的 Chat 覆盖：可见名称配置、yolo、过时 Agent 和 CAS。 */
class ChatServiceIntegrationTest extends PostgresSpringTestSupport {

  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 100L);

  @Autowired private ChatService chatService;
  @Autowired private AgentDefinitionService agentDefinitionService;
  @Autowired private DatabaseTurnResolver turnResolver;
  @MockitoBean private HarnessStore harnessStore;

  @BeforeEach
  void configureThreadWithoutProjectOwner() {
    HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
    when(transaction.findThread(any())).thenReturn(Optional.empty());
    when(harnessStore.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
  }

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
      update.setYoloEnabled(true);
      update.setExpectedVersion("0");
      ChatDTO updated = chatService.updateChat(chatId, update);
      assertEquals("beta", updated.getTitle());
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
      // 同一 EntryPath 先后用于"删除后确定性拒绝"与"同名重建后解析到当前新行"；模型选择指向 dev seed 的 stub/acceptance-stub。
      EntryPath path =
          new EntryPath(
              List.of(
                  new Entry(
                      THREAD_ID,
                      SESSION_ID,
                      null,
                      new RootPayload(
                          new BranchSettings(
                              "default-assistant",
                              new ModelSelection("stub", "acceptance-stub", "default"))),
                      Instant.parse("2026-08-02T00:00:00Z"))));
      TurnResolver.Result resolution = turnResolver.resolve(THREAD_ID, path, null);
      TurnResolver.Rejected rejected = assertInstanceOf(TurnResolver.Rejected.class, resolution);
      assertEquals(DatabaseTurnResolver.REJECTION_CODE, rejected.error().code());
      assertEquals("agent not found: default-assistant", rejected.error().message());

      // 硬删除后同名重建：既有名称引用必须解析到当前新行，而不是继续缺失。
      String reboundSystemPrompt = "Rebound assistant: resolved from the re-created current row.";
      AgentDefinitionCreateDTO rebound = new AgentDefinitionCreateDTO();
      rebound.setName("default-assistant");
      rebound.setModel("stub/acceptance-stub");
      rebound.setVariant("default");
      AgentDefinitionConfigDTO reboundConfig = new AgentDefinitionConfigDTO();
      reboundConfig.setToolIds(List.of());
      reboundConfig.setSkills(List.of());
      reboundConfig.setSubagents(List.of());
      rebound.setConfig(reboundConfig);
      rebound.setSystemPrompt(reboundSystemPrompt);
      AgentDefinitionDTO reboundAgent = agentDefinitionService.createAgent(rebound);
      try {
        TurnResolver.Result reboundResolution = turnResolver.resolve(THREAD_ID, path, null);
        TurnResolver.Resolved resolved =
            assertInstanceOf(TurnResolver.Resolved.class, reboundResolution);
        var leading = resolved.spec().preambleMessages().get(0);
        assertEquals(AgentMessageRole.SYSTEM, leading.role());
        assertTrue(
            textOfPreamble(leading).contains(reboundSystemPrompt),
            "同名重建后的当前行 systemPrompt 必须出现在有效请求的 leading SYSTEM 中");
      } finally {
        agentDefinitionService.deleteAgent("default-assistant", reboundAgent.getVersion());
      }

      ChatUpdateDTO staleAgentUpdate = new ChatUpdateDTO();
      staleAgentUpdate.setTitle("still editable");
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

  /** Chat 不再有 workspace 默认目录：legacy workspacePath 字段在 DTO 边界被严格拒绝，且不残留任何目录状态。 */
  @Test
  void legacyWorkspacePathIsRejectedAndChatCarriesNoDirectoryState() {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("env-default");
    create.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class, () -> create.rejectUnknownField("workspacePath", "."));

    ChatDTO created = chatService.createChat(create);
    String chatId = created.getId();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> {
            ChatUpdateDTO clear = new ChatUpdateDTO();
            clear.setExpectedVersion(created.getVersion());
            clear.rejectUnknownField("workspacePath", null);
          });

      ChatDTO roundTripped = chatService.getChat(chatId);
      assertEquals("env-default", roundTripped.getTitle());
      assertEquals("default-assistant", roundTripped.getAgentName());
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

  private static String textOf(ProviderMessage message) {
    StringBuilder text = new StringBuilder();
    for (ProviderContentBlock content : message.contents()) {
      text.append(((ProviderTextBlock) content).text());
    }
    return text.toString();
  }

  private static String textOfPreamble(AgentMessage message) {
    StringBuilder text = new StringBuilder();
    for (var content : message.contents()) {
      if (content instanceof TextMessageContent textContent) {
        text.append(textContent.text());
      }
    }
    return text.toString();
  }
}
