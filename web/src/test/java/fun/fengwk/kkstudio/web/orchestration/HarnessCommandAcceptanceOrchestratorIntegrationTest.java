package fun.fengwk.kkstudio.web.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.chat.ChatService;
import fun.fengwk.kkstudio.chat.ChatSessionRepository;
import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.StorageUploadTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * HarnessCommandAcceptanceOrchestrator 在 web 组合根的真实 PostgreSQL 回归。
 *
 * <p>测试意图是证明 owner 关系、Session/ROOT/Thread/Command/Work 共享同一事务；失败时预先写入的所有 Harness 行与 relation
 * 行必须一起回滚，且 owner 归属互斥不依赖应用层竞态。
 */
@Import(WebStorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class HarnessCommandAcceptanceOrchestratorIntegrationTest extends WebPostgresTestSupport {

  private static final HistoryEntryPayloadJsonCodec ENTRY_PAYLOADS =
      new HistoryEntryPayloadJsonCodec();

  @Autowired private ChatService chatService;
  @Autowired private HarnessCommandAcceptanceOrchestrator acceptanceService;
  @Autowired private HarnessRuntime harnessRuntime;
  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  private StorageUploadTestSupport storage;

  @BeforeEach
  void setUpStorage() {
    s3Storage.clear();
    storage = new StorageUploadTestSupport(storageUploadService, s3Storage, jdbc);
  }

  @Test
  void newSessionAcceptsOwnerAndHarnessFactsInOneTransaction() {
    UUID chatId = createChat("accept-chat");
    UUID chatSessionId = UUID.randomUUID();
    UUID chatThreadId = UUID.randomUUID();

    accept(
        new SessionOwner("CHAT", chatId.toString()),
        chatSessionId,
        chatThreadId,
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello")))));

    assertEquals(1, count("chat_session", "session_id", chatSessionId));
    assertEquals(1, count("harness_session", "id", chatSessionId));
    assertEquals(1, count("harness_entry", "session_id", chatSessionId));
    assertEquals(1, count("harness_thread", "id", chatThreadId));
    assertEquals(1, count("harness_thread_command", "thread_id", chatThreadId));
    assertEquals(1, count("durable_work", "target_id", chatThreadId));
  }

  /** 既有 Thread 的继续写入不经 owner 授权，但必须保持产品合法的 SET_* 前缀与末尾用户消息。 */
  @Test
  void threadTargetContinuationPreservesCommandPrefix() {
    UUID chatId = createChat("thread-target");
    SessionOwner owner = new SessionOwner("CHAT", chatId.toString());
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    AcceptedCommands initial =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("first")))),
                        UUID.randomUUID()))));

    AcceptedCommands continued =
        acceptanceService.acceptOnThread(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(
                    threadId,
                    initial.thread().headEntryId(),
                    initial.thread().nextCommandSequence()),
                List.of(
                    new NewThreadCommand(
                        new SetAgentCommandPayload("default-assistant"), UUID.randomUUID()),
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("second")))),
                        UUID.randomUUID()))));

    assertEquals(sessionId, continued.thread().sessionId());
    assertEquals(2, continued.acceptedCommands().size());
    assertInstanceOf(
        SetAgentCommandPayload.class, continued.acceptedCommands().getFirst().payload());
    assertInstanceOf(
        UserMessageCommandPayload.class, continued.acceptedCommands().getLast().payload());
    assertEquals(3, count("harness_thread_command", "thread_id", threadId));
  }

  @Test
  void failedAttachmentAcceptanceRollsBackRelationAndAllHarnessFacts() {
    UUID chatId = createChat("rollback-chat");
    StorageUploadDTO pending =
        storage.reserve(
            "pending.txt",
            "text/plain",
            7,
            storage.sha256Hex("pending".getBytes(StandardCharsets.UTF_8)));
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(new AttachmentMessageContent(UUID.fromString(pending.getId())))));

    DomainException conflict =
        assertThrows(
            DomainException.class,
            () ->
                accept(new SessionOwner("CHAT", chatId.toString()), sessionId, threadId, payload));
    assertEquals(DomainErrorCode.CONFLICT, conflict.code());
    assertEquals("STORAGE_VERIFICATION", conflict.reason());

    assertEquals(1, count("storage_upload", "id", UUID.fromString(pending.getId())));
    assertEquals(0, count("chat_session", "session_id", sessionId));
    assertEquals(0, count("harness_session", "id", sessionId));
    assertEquals(0, count("harness_entry", "session_id", sessionId));
    assertEquals(0, count("session_blob_ref", "session_id", sessionId));
    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("harness_thread_command", "thread_id", threadId));
    assertEquals(0, count("durable_work", "target_id", threadId));
  }

  @Test
  void existingSessionResourceCanBeReusedWithoutAnotherRetain() {
    UUID chatId = createChat("resource-reuse");
    SessionOwner owner = new SessionOwner("CHAT", chatId.toString());
    byte[] content = "durable resource".getBytes(StandardCharsets.UTF_8);
    String uploadId = storage.completeUpload("resource.txt", content);
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    AcceptedCommands initial =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER,
                                List.of(new AttachmentMessageContent(UUID.fromString(uploadId))))),
                        UUID.randomUUID()))));
    UUID blobId =
        jdbc.queryForObject(
            "select blob_id from session_blob_ref where session_id = ?", UUID.class, sessionId);
    long refCountBefore =
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId);

    UUID entryThreadId = UUID.randomUUID();
    ResourceMessageContent resource =
        ResourceMessageContent.media(blobId, "resource.txt", "preview");
    AcceptedCommands reused =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewThread(
                    sessionId, initial.rootEntry().id(), entryThreadId, "branch", false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(AgentMessageRole.USER, List.of(resource))),
                        UUID.randomUUID()))));

    UserMessageCommandPayload acceptedPayload =
        assertInstanceOf(
            UserMessageCommandPayload.class, reused.acceptedCommands().getFirst().payload());
    assertEquals(resource, acceptedPayload.message().contents().getFirst());
    assertEquals(
        refCountBefore,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));
    assertEquals(1, count("session_blob_ref", "session_id", sessionId));

    // 同一 blob 不能被带入没有 ref 的新 Session；relation 与全部 Harness facts 必须一起回滚。
    UUID foreignSessionId = UUID.randomUUID();
    UUID foreignThreadId = UUID.randomUUID();
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () ->
                    acceptanceService.accept(
                        owner,
                        new AcceptCommandsCommand(
                            new AcceptCommandsTarget.NewRootSession(
                                foreignSessionId, foreignThreadId, settings(), false),
                            List.of(
                                new NewThreadCommand(
                                    new UserMessageCommandPayload(
                                        new AgentMessage(AgentMessageRole.USER, List.of(resource))),
                                    UUID.randomUUID())))))
            .code());
    assertEquals(0, count("chat_session", "session_id", foreignSessionId));
    assertEquals(0, count("harness_session", "id", foreignSessionId));
    assertEquals(0, count("harness_thread", "id", foreignThreadId));
    assertEquals(
        refCountBefore,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));
  }

  /** Fork 把历史中的资源引用复制到新 Session，并使新 Session 成为独立持有者。 */
  @Test
  void forkedSessionRetainsCopiedResourceHistory() {
    UUID chatId = createChat("fork-resource");
    SessionOwner owner = new SessionOwner("CHAT", chatId.toString());
    String uploadId =
        storage.completeUpload("resource.txt", "forked resource".getBytes(StandardCharsets.UTF_8));
    UUID sourceSessionId = UUID.randomUUID();
    UUID sourceThreadId = UUID.randomUUID();
    AcceptedCommands source =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(
                    sourceSessionId, sourceThreadId, settings(), false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER,
                                List.of(new AttachmentMessageContent(UUID.fromString(uploadId))))),
                        UUID.randomUUID()))));
    UUID blobId =
        jdbc.queryForObject(
            "select blob_id from session_blob_ref where session_id = ?",
            UUID.class,
            sourceSessionId);
    UUID cutEntryId =
        appendResourceTurn(sourceSessionId, sourceThreadId, source.rootEntry().id(), blobId);
    UUID forkSessionId = UUID.randomUUID();
    UUID forkThreadId = UUID.randomUUID();

    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewForkedSession(
                sourceThreadId, cutEntryId, forkSessionId, forkThreadId, false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent("fork")))),
                    UUID.randomUUID()))));

    assertEquals(1, count("session_blob_ref", "session_id", sourceSessionId));
    assertEquals(1, count("session_blob_ref", "session_id", forkSessionId));
    assertEquals(
        2L,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));
  }

  @Test
  void acceptanceRejectsCrossOwnerSessionAndReplay() {
    UUID chat1Id = createChat("chat-owner-1");
    UUID chat2Id = createChat("chat-owner-2");
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("owner"))));

    accept(
        new SessionOwner("CHAT", chat1Id.toString()), sessionId, threadId, payload, idempotencyKey);

    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () ->
                    accept(
                        new SessionOwner("CHAT", chat2Id.toString()),
                        sessionId,
                        UUID.randomUUID(),
                        payload))
            .code());
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () ->
                    accept(
                        new SessionOwner("CHAT", chat2Id.toString()),
                        sessionId,
                        threadId,
                        payload,
                        idempotencyKey))
            .code());

    assertEquals(1, chatSessionRepository.listSessionIds(chat1Id).size());
    assertEquals(0, chatSessionRepository.listSessionIds(chat2Id).size());
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
  }

  @Test
  void productOwnerCannotClaimAnExistingInternalSessionThroughReplay() {
    UUID chatId = createChat("internal-session-owner");
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent("internal")))),
                    UUID.randomUUID())));
    harnessRuntime.acceptCommands(command, AcceptancePreflight.IDENTITY);

    // 逆证：无 owner relation 的内部 Session 即使能命中 Runtime exact replay，也不能被产品 owner 接管。
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () ->
                    acceptanceService.accept(new SessionOwner("CHAT", chatId.toString()), command))
            .code());
    assertEquals(0, count("chat_session", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
  }

  private UUID createChat(String title) {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle(title);
    create.setAgentName("default-assistant");
    return UUID.fromString(chatService.createChat(create).getId());
  }

  private void accept(
      SessionOwner owner, UUID sessionId, UUID threadId, UserMessageCommandPayload payload) {
    accept(owner, sessionId, threadId, payload, UUID.randomUUID());
  }

  private void accept(
      SessionOwner owner,
      UUID sessionId,
      UUID threadId,
      UserMessageCommandPayload payload,
      UUID idempotencyKey) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false),
            List.of(new NewThreadCommand(payload, idempotencyKey))));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        "default-assistant",
        BuiltinExecution.of(new ModelSelection("acceptance-stub", "default")),
        null);
  }

  private UUID appendResourceTurn(UUID sessionId, UUID threadId, UUID rootEntryId, UUID blobId) {
    UUID turnStartId =
        insertEntry(
            sessionId,
            rootEntryId,
            new TurnStartPayload(TurnStartReason.INPUT, settings(), threadId));
    UUID messageId =
        insertEntry(
            sessionId,
            turnStartId,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(ResourceMessageContent.media(blobId, "resource.txt", null))),
                null,
                null));
    UUID turnEndId =
        insertEntry(
            sessionId,
            messageId,
            new TurnEndPayload(
                turnStartId, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null));
    jdbc.update(
        "update harness_thread set head_entry_id = ?, updated_at = now() where id = ?",
        turnEndId,
        threadId);
    return turnEndId;
  }

  private UUID insertEntry(UUID sessionId, UUID parentEntryId, EntryPayload payload) {
    UUID entryId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, ?, ?::jsonb, now())",
        entryId,
        sessionId,
        parentEntryId,
        payload.type().name(),
        ENTRY_PAYLOADS.encode(payload));
    return entryId;
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }
}
