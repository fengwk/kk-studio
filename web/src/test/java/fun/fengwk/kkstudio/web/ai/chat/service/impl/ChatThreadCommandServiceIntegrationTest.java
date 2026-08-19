package fun.fengwk.kkstudio.web.ai.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.web.ai.chat.ChatIntegrationSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.S3WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Chat 应用 use-case 事务边界（{@link ChatThreadCommandService}）在 web 组合根上的 PostgreSQL 集成测试：真实 Harness
 * store（web 装配）+ 内存 S3。覆盖 createThread 关联、附件物化为 durable RESOURCE、hash 重放不二次消费 upload、 PENDING
 * 附件整体回滚。
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
class ChatThreadCommandServiceIntegrationTest extends S3WebPostgresTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private ChatThreadCommandService chatThreadCommandService;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  private ChatIntegrationSupport storage;
  private String chatId;

  @BeforeEach
  void setUpChat() {
    s3Storage.clear();
    storage = new ChatIntegrationSupport(storageUploadService, s3Storage, jdbc);
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("attachment-chat");
    create.setAgentName("default-assistant");
    chatId = chatService.createChat(create).getId();
  }

  @Test
  void createChatThreadAssociatesAndSubmitsTextCommands() {
    CreatedThread created = createThread("text-thread");
    ThreadCommandBatch batch =
        batch(
            created,
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER, List.of(new TextMessageContent("hello")))),
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(
                        new UserMessageCommandPayload(
                            new AgentMessage(
                                AgentMessageRole.USER,
                                List.of(new TextMessageContent("hello"))))))));
    List<ThreadCommand> enqueued = chatThreadCommandService.submitCommands(batch);
    assertEquals(1, enqueued.size());
    assertEquals(batch.commands().get(0).requestHash(), enqueued.get(0).requestHash());
    assertNotNull(enqueued.get(0).payload());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            created.thread().id()));
  }

  @Test
  void submitAttachmentConsumesUploadIntoDurableResource() {
    byte[] content = "my report".getBytes(StandardCharsets.UTF_8);
    String uploadId = storage.completeUpload("quarterly.txt", content);
    CreatedThread created = createThread("attachment-thread");

    UUID clientCommandId = UUID.randomUUID();
    AttachmentMessageContent attachment = new AttachmentMessageContent(UUID.fromString(uploadId));
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(attachment)));
    ThreadCommandBatch batch =
        batch(
            created,
            List.of(
                new NewThreadCommand(
                    payload, clientCommandId, ThreadCommandPayloadJsonCodec.requestHash(payload))));

    List<ThreadCommand> enqueued = chatThreadCommandService.submitCommands(batch);

    UserMessageCommandPayload durable =
        assertInstanceOf(UserMessageCommandPayload.class, enqueued.get(0).payload());
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, durable.message().contents().get(0));
    assertEquals("quarterly.txt", resource.name(), "authoritative filename must be materialized");
    assertEquals(null, resource.preview());
    String blobId = resource.blobId().toString();

    // upload 行已消费删除；session ref 保留 blob 引用（upload 释放、ref 顶替 → ref_count 仍为 1）。
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(uploadId)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            created.session().id(),
            resource.blobId()));
    assertEquals(1L, storage.blobRefCount(blobId));

    // 幂等重放：同 clientCommandId + 同 hash 不消费任何东西，返回既有 durable command。
    List<ThreadCommand> replayed = chatThreadCommandService.submitCommands(batch);
    assertEquals(1, replayed.size());
    assertEquals(enqueued.get(0).sequence(), replayed.get(0).sequence());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            created.thread().id()),
        "replay must not insert a second command");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ?",
            Integer.class,
            created.session().id()),
        "replay must not touch blob references");
  }

  @Test
  void pendingAttachmentFailsAtomicallyAndLeavesNoTrace() {
    // PENDING（未 complete）upload：消费必须确定性失败且整体回滚。
    byte[] content = "incomplete".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "pending.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    CreatedThread created = createThread("pending-thread");

    AttachmentMessageContent attachment =
        new AttachmentMessageContent(UUID.fromString(pending.getId()));
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(attachment)));
    ThreadCommandBatch batch =
        batch(
            created,
            List.of(
                new NewThreadCommand(
                    payload,
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(payload))));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> chatThreadCommandService.submitCommands(batch));
    assertTrue(error.getMessage().contains("cannot be consumed"), "actual: " + error.getMessage());

    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(pending.getId())),
        "failed consumption must leave the upload row untouched");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            created.thread().id()),
        "failed batch must not leave durable commands");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ?",
            Integer.class,
            created.session().id()));
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
  }

  @Test
  void concurrentSameAttachmentBatchHasExactlyOneUploadConsumption() throws Exception {
    byte[] content = "concurrent report".getBytes(StandardCharsets.UTF_8);
    String uploadId = storage.completeUpload("concurrent.txt", content);
    CreatedThread created = createThread("concurrent-thread");

    AttachmentMessageContent attachment = new AttachmentMessageContent(UUID.fromString(uploadId));
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, List.of(attachment)));
    ThreadCommandBatch batch =
        batch(
            created,
            List.of(
                new NewThreadCommand(
                    payload,
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(payload))));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<ThreadCommand> a;
    List<ThreadCommand> b;
    try {
      CyclicBarrier start = new CyclicBarrier(2);
      Future<List<ThreadCommand>> first =
          pool.submit(
              () -> {
                start.await();
                return chatThreadCommandService.submitCommands(batch);
              });
      Future<List<ThreadCommand>> second =
          pool.submit(
              () -> {
                start.await();
                return chatThreadCommandService.submitCommands(batch);
              });
      a = first.get(30, TimeUnit.SECONDS);
      b = second.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    // 线程锁串行：一个 CAS 赢家插入并消费 upload，另一个幂等 replay 返回同一行、绝不二次消费。
    assertEquals(1, a.size());
    assertEquals(1, b.size());
    assertEquals(a.get(0).sequence(), b.get(0).sequence());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            created.thread().id()));
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
    UUID blobId = jdbc.queryForObject("select id from storage_blob", UUID.class);
    assertEquals(1L, storage.blobRefCount(blobId.toString()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ?",
            Integer.class,
            created.session().id()));
    assertTrue(s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)));
  }

  @Test
  void twoReadyUploadsForSameBlobConsumeBothIntoOneSessionRef() {
    byte[] content = "same blob content".getBytes(StandardCharsets.UTF_8);
    String uploadA = storage.completeUpload("same-a.txt", content);
    String uploadB = storage.completeUpload("same-b.txt", content);
    CreatedThread created = createThread("two-ready-thread");

    List<NewThreadCommand> commands = new ArrayList<>();
    for (String uploadId : List.of(uploadA, uploadB)) {
      AttachmentMessageContent attachment = new AttachmentMessageContent(UUID.fromString(uploadId));
      UserMessageCommandPayload payload =
          new UserMessageCommandPayload(
              new AgentMessage(AgentMessageRole.USER, List.of(attachment)));
      commands.add(
          new NewThreadCommand(
              payload, UUID.randomUUID(), ThreadCommandPayloadJsonCodec.requestHash(payload)));
    }
    List<ThreadCommand> enqueued =
        chatThreadCommandService.submitCommands(batch(created, commands));
    assertEquals(2, enqueued.size());

    // 两个 READY 上传去重到同一 blob：消费各自上传（upload 持有 retain，消费 = retainRef + 删 upload），
    // ref 行幂等 → 最终 ref_count 恰好 1、ref 行 1 条。
    UUID blobId = jdbc.queryForObject("select id from storage_blob", UUID.class);
    assertEquals(1, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
    assertEquals(1L, storage.blobRefCount(blobId.toString()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ?",
            Integer.class,
            created.session().id()));
    assertEquals(
        2, jdbc.queryForObject("select count(*) from harness_thread_command", Integer.class));
    ResourceMessageContent first =
        assertInstanceOf(
            ResourceMessageContent.class,
            ((UserMessageCommandPayload) enqueued.get(0).payload()).message().contents().get(0));
    ResourceMessageContent second =
        assertInstanceOf(
            ResourceMessageContent.class,
            ((UserMessageCommandPayload) enqueued.get(1).payload()).message().contents().get(0));
    assertEquals(blobId, first.blobId());
    assertEquals(blobId, second.blobId());
    assertEquals("same-a.txt", first.name());
    assertEquals("same-b.txt", second.name());
  }

  private CreatedThread createThread(String title) {
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

  private ThreadCommandBatch batch(CreatedThread created, List<NewThreadCommand> commands) {
    return new ThreadCommandBatch(
        created.thread().id(),
        created.thread().headEntryId(),
        created.thread().nextCommandSequence(),
        commands);
  }
}
