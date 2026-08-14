package fun.fengwk.kkstudio.web.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadCommandService;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chat 深删除在 web 组合根上的 PostgreSQL 集成测试：真实 Harness store + 内存 S3。
 *
 * <p>覆盖带绑定 Thread + blob ref 的深删除（FK 顺序删除全部 Harness 状态、ref release 到零、blob 对象回收），以及 无绑定 Thread
 * 的普通删除路径。
 */
@Import(WebStorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.enabled=true",
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=AKIAIOSFODNN7EXAMPLE",
      "kk-studio.storage.s3.secret-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    })
class ChatDeepDeleteIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private ChatThreadCommandService chatThreadCommandService;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  private ChatIntegrationSupport storage;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
    storage = new ChatIntegrationSupport(storageUploadService, s3Storage, jdbc);
  }

  @Test
  void deleteChatWithBoundThreadReleasesEverythingInFkOrder() {
    String chatId = createChat("deep-delete");
    CreatedThread created = createThread(chatId, "deep-delete-thread");

    // 附件消息：blob ref 由 session 持有。
    byte[] content = "deep delete me".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "deep.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    String blobId = storageUploadService.complete(UUID.fromString(pending.getId())).getBlobId();
    AttachmentMessageContent attachment =
        new AttachmentMessageContent(UUID.fromString(pending.getId()));
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(attachment)));
    ThreadCommandBatch batch =
        new ThreadCommandBatch(
            created.thread().id(),
            created.thread().headEntryId(),
            created.thread().nextCommandSequence(),
            List.of(
                new NewThreadCommand(
                    payload,
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(payload))));
    chatThreadCommandService.submitCommands(batch);
    UUID sessionId = created.session().id();
    UUID threadId = created.thread().id();
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ?",
            Integer.class,
            sessionId));

    chatService.deleteChat(chatId, "0");

    // Chat 行与绑定关系删除。
    assertThrows(AiResourceNotFoundException.class, () -> chatService.getChat(chatId));
    assertEquals(0, jdbc.queryForObject("select count(*) from chat_thread", Integer.class));
    // FK 顺序：Work/Invocation/Command/Thread/Entry/Session 全部清空。
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_work where target_id = ?", Integer.class, threadId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            threadId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_thread where id = ?", Integer.class, threadId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_entry where session_id = ?", Integer.class, sessionId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_session where id = ?", Integer.class, sessionId));
    // blob ref 逐行 release：ref 行清空，blob 引用归零后对象与行回收。
    assertEquals(
        0, jdbc.queryForObject("select count(*) from harness_session_blob_ref", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ?",
            Integer.class,
            UUID.fromString(blobId)));
    assertTrue(
        !s3Storage.hasObject(StorageObjectKeys.blobOriginal(UUID.fromString(blobId))),
        "blob object must be reclaimed after deep delete");
  }

  @Test
  void deleteChatWithoutThreadsStillDeletesChatRow() {
    String chatId = createChat("plain-delete");
    chatService.deleteChat(chatId, "0");
    assertThrows(AiResourceNotFoundException.class, () -> chatService.getChat(chatId));
  }

  @Test
  void deleteChatLocksMultipleThreadsByAscendingHarnessUuidOrder() {
    String chatId = createChat("multi-thread-delete");
    UUID firstThreadId = createThread(chatId, "first").thread().id();
    UUID secondThreadId = createThread(chatId, "second").thread().id();
    UUID lowerThreadId =
        UuidOrder.compare(firstThreadId, secondThreadId) < 0 ? firstThreadId : secondThreadId;
    UUID higherThreadId = lowerThreadId.equals(firstThreadId) ? secondThreadId : firstThreadId;

    // Chat 列表仍按最近关联排序；这里确定性构造与 Harness 锁顺序相反的返回顺序。
    jdbc.update(
        "update chat_thread set created_at = ? where thread_id = ?",
        Timestamp.from(Instant.parse("2026-08-14T10:01:00Z")),
        higherThreadId);
    jdbc.update(
        "update chat_thread set created_at = ? where thread_id = ?",
        Timestamp.from(Instant.parse("2026-08-14T10:00:00Z")),
        lowerThreadId);

    chatService.deleteChat(chatId, "0");

    assertThrows(AiResourceNotFoundException.class, () -> chatService.getChat(chatId));
    assertEquals(0, jdbc.queryForObject("select count(*) from chat_thread", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_thread where id in (?, ?)",
            Integer.class,
            firstThreadId,
            secondThreadId));
  }

  private String createChat(String title) {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle(title);
    create.setAgentName("default-assistant");
    return chatService.createChat(create).getId();
  }

  private CreatedThread createThread(String chatId, String title) {
    return chatThreadCommandService.createChatThread(
        chatId,
        new CreateThreadCommand(
            new BranchSettings(
                null,
                "default-assistant",
                new ModelSelection("stub", "acceptance-stub", "default"),
                List.of()),
            false));
  }
}
