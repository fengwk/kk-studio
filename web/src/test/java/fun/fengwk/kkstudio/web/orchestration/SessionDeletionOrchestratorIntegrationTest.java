package fun.fengwk.kkstudio.web.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.ai.chat.ChatIntegrationSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SessionDeletionOrchestrator 在 web 组合根的真实 PostgreSQL/S3 回归。
 *
 * <p>测试意图是覆盖两阶段 Session→Thread 锁序，并确认深删只释放目标 owner 的 Session blob ref：同一 Blob 被另一 owner 引用时，另一侧的
 * Session、Thread、relation 与对象都必须保留。
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
class SessionDeletionOrchestratorIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private HarnessCommandAcceptanceOrchestrator acceptanceService;
  @Autowired private SessionDeletionOrchestrator deletionService;
  @Autowired private ChatSessionRepository chatSessionRepository;
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
  void deepDeleteReleasesOnlyTargetOwnerBlobRefs() {
    UUID chat1Id = createChat("delete-chat-1");
    UUID chat2Id = createChat("delete-chat-2");
    byte[] content = "shared blob".getBytes(StandardCharsets.UTF_8);
    String upload1 = storage.completeUpload("chat1.txt", content);
    String upload2 = storage.completeUpload("chat2.txt", content);
    UUID session1 = UUID.randomUUID();
    UUID session2 = UUID.randomUUID();
    UUID thread1 = UUID.randomUUID();
    UUID thread2 = UUID.randomUUID();

    acceptAttachment(new OwnerRef.Chat(chat1Id), session1, thread1, UUID.fromString(upload1));
    acceptAttachment(new OwnerRef.Chat(chat2Id), session2, thread2, UUID.fromString(upload2));

    UUID blobId =
        jdbc.queryForObject(
            "select blob_id from session_blob_ref where session_id = ?", UUID.class, session1);
    assertEquals(
        2L,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));

    deletionService.deleteSessionsByOwner(new OwnerRef.Chat(chat1Id));

    assertEquals(0, count("chat_session", "session_id", session1));
    assertEquals(0, count("harness_session", "id", session1));
    assertEquals(0, count("harness_thread", "id", thread1));
    assertEquals(0, count("session_blob_ref", "session_id", session1));
    assertEquals(1, count("chat_session", "session_id", session2));
    assertEquals(1, count("harness_session", "id", session2));
    assertEquals(1, count("harness_thread", "id", thread2));
    assertEquals(1, count("session_blob_ref", "session_id", session2));
    assertEquals(
        1L,
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId));
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)),
        "the shared blob must remain while the other Chat Session still references it");
  }

  @Test
  void deletionLocksAllSessionsBeforeThreadsWhenRelationOrderDiffers() {
    UUID chatId = createChat("ordered-delete");
    UUID firstSession = new UUID(0L, 2L);
    UUID secondSession = new UUID(0L, 1L);
    UUID firstThread = new UUID(0L, 3L);
    UUID secondThread = new UUID(0L, 4L);
    acceptText(new OwnerRef.Chat(chatId), firstSession, firstThread, "first");
    acceptText(new OwnerRef.Chat(chatId), secondSession, secondThread, "second");

    // 归属枚举顺序刻意与 UUID 锁顺序相反；删除服务必须先收集并锁完 Session，再进入 Thread 阶段。
    jdbc.update(
        "update chat_session set created_at = ? where session_id = ?",
        Timestamp.from(Instant.parse("2026-08-20T00:01:00Z")),
        firstSession);
    jdbc.update(
        "update chat_session set created_at = ? where session_id = ?",
        Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        secondSession);

    // 归属枚举与 Session UUID 顺序相反，按 Session 分组后的 Thread 又是 4 -> 3；实现必须分别全局排序两个 rank。
    assertEquals(
        List.of(firstSession, secondSession), chatSessionRepository.listSessionIds(chatId));
    deletionService.deleteSessionsByOwner(new OwnerRef.Chat(chatId));

    assertEquals(0, count("chat_session", "chat_id", chatId));
    assertEquals(0, count("harness_session", "id", firstSession));
    assertEquals(0, count("harness_session", "id", secondSession));
    assertEquals(0, count("harness_thread", "id", firstThread));
    assertEquals(0, count("harness_thread", "id", secondThread));
  }

  private void acceptText(OwnerRef owner, UUID sessionId, UUID threadId, String text) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
                    UUID.randomUUID()))));
  }

  private void acceptAttachment(OwnerRef owner, UUID sessionId, UUID threadId, UUID uploadId) {
    acceptanceService.accept(
        owner,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(sessionId, threadId, settings(), null, false),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(new AttachmentMessageContent(uploadId)))),
                    UUID.randomUUID()))));
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        "default-assistant", new ModelSelection("stub", "acceptance-stub", "default"), null);
  }

  private UUID createChat(String title) {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle(title);
    create.setAgentName("default-assistant");
    return UUID.fromString(chatService.createChat(create).getId());
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }
}
