package fun.fengwk.kkstudio.web.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.studio.StudioCommandAcceptanceService;
import fun.fengwk.kkstudio.core.studio.StudioOwner;
import fun.fengwk.kkstudio.core.studio.StudioOwnerType;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.web.ai.chat.ChatIntegrationSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.S3WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * StudioCommandAcceptanceService 在 web 组合根的真实 PostgreSQL 回归。
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
      "kk-studio.storage.s3.access-key=AKIAIOSFODNN7EXAMPLE",
      "kk-studio.storage.s3.secret-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    })
class StudioCommandAcceptanceServiceIntegrationTest extends S3WebPostgresTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private CanvasCommandService canvasCommandService;
  @Autowired private StudioCommandAcceptanceService acceptanceService;
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
        new StudioOwner(StudioOwnerType.CHAT, chatId),
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
        new StudioOwner(StudioOwnerType.CANVAS, canvasId),
        canvasSessionId,
        canvasThreadId,
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("canvas")))));
    assertEquals(1, count("canvas_session", "session_id", canvasSessionId));
    assertEquals(1, count("harness_thread", "id", canvasThreadId));
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
        () -> accept(new StudioOwner(StudioOwnerType.CHAT, chatId), sessionId, threadId, payload));

    assertEquals(1, count("storage_upload", "id", UUID.fromString(pending.getId())));
    assertEquals(0, count("chat_session", "session_id", sessionId));
    assertEquals(0, count("harness_session", "id", sessionId));
    assertEquals(0, count("harness_entry", "session_id", sessionId));
    assertEquals(0, count("harness_session_blob_ref", "session_id", sessionId));
    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("harness_thread_command", "thread_id", threadId));
    assertEquals(0, count("harness_work", "target_id", threadId));
  }

  @Test
  void acceptanceRejectsCrossOwnerSessionAndReplay() {
    UUID chatId = createChat("chat-owner");
    UUID canvasId = canvasCommandService.createCanvas("canvas-owner").id();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID clientCommandId = UUID.randomUUID();
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("owner"))));

    accept(
        new StudioOwner(StudioOwnerType.CHAT, chatId),
        sessionId,
        threadId,
        payload,
        clientCommandId);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            accept(
                new StudioOwner(StudioOwnerType.CANVAS, canvasId),
                sessionId,
                UUID.randomUUID(),
                payload));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            accept(
                new StudioOwner(StudioOwnerType.CANVAS, canvasId),
                sessionId,
                threadId,
                payload,
                clientCommandId));

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
        () -> acceptanceService.accept(new StudioOwner(StudioOwnerType.CHAT, chatId), command));
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
      StudioOwner owner, UUID sessionId, UUID threadId, UserMessageCommandPayload payload) {
    accept(owner, sessionId, threadId, payload, UUID.randomUUID());
  }

  private void accept(
      StudioOwner owner,
      UUID sessionId,
      UUID threadId,
      UserMessageCommandPayload payload,
      UUID clientCommandId) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
            List.of(new NewThreadCommand(payload, clientCommandId))));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        null,
        "default-assistant",
        new ModelSelection("stub", "acceptance-stub", "default"),
        List.of());
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }
}
