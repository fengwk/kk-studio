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

import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.web.ai.chat.ChatIntegrationSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.S3WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * HarnessCommandAcceptanceOrchestrator 在 web 组合根的真实 PostgreSQL 回归。
 *
 * <p>测试意图是证明 owner 关系、Session/ROOT/Thread/Command/Work 共享同一事务；失败时预先写入的所有 Harness 行与 relation
 * 行必须一起回滚，且 Chat/Canvas 归属互斥不依赖应用层竞态。
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
class HarnessCommandAcceptanceOrchestratorIntegrationTest extends S3WebPostgresTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private CanvasCommandService canvasCommandService;
  @Autowired private HarnessCommandAcceptanceOrchestrator acceptanceService;
  @Autowired private HarnessRuntime harnessRuntime;
  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private CanvasSessionRepository canvasSessionRepository;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  private ChatIntegrationSupport storage;

  @BeforeEach
  void setUpStorage() {
    s3Storage.clear();
    storage = new ChatIntegrationSupport(storageUploadService, s3Storage, jdbc);
  }

  @Test
  void newSessionAcceptsOwnerAndHarnessFactsInOneTransaction() {
    UUID chatId = createChat("accept-chat");
    UUID chatSessionId = UUID.randomUUID();
    UUID chatThreadId = UUID.randomUUID();

    accept(
        new OwnerRef(OwnerType.CHAT, chatId),
        chatSessionId,
        chatThreadId,
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello")))));

    assertEquals(1, count("chat_session", "session_id", chatSessionId));
    assertEquals(1, count("harness_session", "id", chatSessionId));
    assertEquals(1, count("harness_entry", "session_id", chatSessionId));
    assertEquals(1, count("harness_thread", "id", chatThreadId));
    assertEquals(1, count("harness_thread_command", "thread_id", chatThreadId));
    assertEquals(1, count("harness_work", "target_id", chatThreadId));

    UUID canvasId = canvasCommandService.createCanvas("accept-canvas").id();
    UUID canvasSessionId = UUID.randomUUID();
    UUID canvasThreadId = UUID.randomUUID();
    accept(
        new OwnerRef(OwnerType.CANVAS, canvasId),
        canvasSessionId,
        canvasThreadId,
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("canvas")))));
    assertEquals(1, count("canvas_session", "session_id", canvasSessionId));
    assertEquals(1, count("harness_thread", "id", canvasThreadId));
  }

  /** 既有 Thread 必须经所属 Session 授权，并保持产品合法的 SET_* 前缀与末尾用户消息。 */
  @Test
  void threadTargetAcceptsOwnedSessionAndPreservesCommandPrefix() {
    UUID chatId = createChat("thread-target");
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, chatId);
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    AcceptedCommands initial =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
                List.of(
                    new NewThreadCommand(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("first")))),
                        UUID.randomUUID()))));

    AcceptedCommands continued =
        acceptanceService.accept(
            owner,
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

    assertThrows(
        IllegalArgumentException.class,
        () -> accept(new OwnerRef(OwnerType.CHAT, chatId), sessionId, threadId, payload));

    assertEquals(1, count("storage_upload", "id", UUID.fromString(pending.getId())));
    assertEquals(0, count("chat_session", "session_id", sessionId));
    assertEquals(0, count("harness_session", "id", sessionId));
    assertEquals(0, count("harness_entry", "session_id", sessionId));
    assertEquals(0, count("session_blob_ref", "session_id", sessionId));
    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("harness_thread_command", "thread_id", threadId));
    assertEquals(0, count("harness_work", "target_id", threadId));
  }

  @Test
  void existingSessionResourceCanBeReusedWithoutAnotherRetain() {
    UUID chatId = createChat("resource-reuse");
    OwnerRef owner = new OwnerRef(OwnerType.CHAT, chatId);
    byte[] content = "durable resource".getBytes(StandardCharsets.UTF_8);
    String uploadId = storage.completeUpload("resource.txt", content);
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    AcceptedCommands initial =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
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
    ResourceMessageContent resource = new ResourceMessageContent(blobId, "resource.txt", "preview");
    AcceptedCommands reused =
        acceptanceService.accept(
            owner,
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewThread(
                    sessionId, initial.rootEntry().id(), entryThreadId, false),
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
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptanceService.accept(
                owner,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewSession(
                        foreignSessionId, foreignThreadId, settings(), null, false),
                    List.of(
                        new NewThreadCommand(
                            new UserMessageCommandPayload(
                                new AgentMessage(AgentMessageRole.USER, List.of(resource))),
                            UUID.randomUUID())))));
    assertEquals(0, count("chat_session", "session_id", foreignSessionId));
    assertEquals(0, count("harness_session", "id", foreignSessionId));
    assertEquals(0, count("harness_thread", "id", foreignThreadId));
    assertEquals(
        refCountBefore,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));
  }

  @Test
  void acceptanceRejectsCrossOwnerSessionAndReplay() {
    UUID chatId = createChat("chat-owner");
    UUID canvasId = canvasCommandService.createCanvas("canvas-owner").id();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("owner"))));

    accept(new OwnerRef(OwnerType.CHAT, chatId), sessionId, threadId, payload, idempotencyKey);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            accept(
                new OwnerRef(OwnerType.CANVAS, canvasId), sessionId, UUID.randomUUID(), payload));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            accept(
                new OwnerRef(OwnerType.CANVAS, canvasId),
                sessionId,
                threadId,
                payload,
                idempotencyKey));

    assertEquals(1, chatSessionRepository.listSessionIds(chatId).size());
    assertEquals(0, canvasSessionRepository.listSessionIds(canvasId).size());
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
            new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent("internal")))),
                    UUID.randomUUID())));
    harnessRuntime.acceptCommands(command, AcceptancePreflight.IDENTITY);

    // 逆证：无 owner relation 的内部 Session 即使能命中 Runtime exact replay，也不能被产品 owner 接管。
    assertThrows(
        IllegalArgumentException.class,
        () -> acceptanceService.accept(new OwnerRef(OwnerType.CHAT, chatId), command));
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
      OwnerRef owner, UUID sessionId, UUID threadId, UserMessageCommandPayload payload) {
    accept(owner, sessionId, threadId, payload, UUID.randomUUID());
  }

  private void accept(
      OwnerRef owner,
      UUID sessionId,
      UUID threadId,
      UserMessageCommandPayload payload,
      UUID idempotencyKey) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
            List.of(new NewThreadCommand(payload, idempotencyKey))));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        null, "default-assistant", new ModelSelection("stub", "acceptance-stub", "default"));
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }
}
