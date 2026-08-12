package fun.fengwk.kkstudio.web.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.studio.thread.CanvasThreadService;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.ai.chat.ChatIntegrationSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Canvas 首发附件与深删除在 web 组合根上的 PostgreSQL + 内存 S3 集成覆盖。 */
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
class CanvasThreadAttachmentIntegrationTest extends WebPostgresTestSupport {

  @Autowired private CanvasCommandService canvasCommandService;
  @Autowired private CanvasThreadService canvasThreadService;
  @Autowired private HarnessRuntime runtime;
  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;

  private ChatIntegrationSupport storage;

  @BeforeEach
  void resetStorage() {
    s3Storage.clear();
    storage = new ChatIntegrationSupport(storageUploadService, s3Storage, jdbc);
  }

  @Test
  void firstAttachmentMaterializesAndCanvasDeleteReleasesThreadSessionAndBlob() {
    byte[] content = "canvas attachment".getBytes(StandardCharsets.UTF_8);
    String uploadId = storage.completeUpload("canvas.txt", content);
    UUID uploadUuid = UUID.fromString(uploadId);
    UUID commandId = UUID.randomUUID();
    CanvasDocument canvas = canvasCommandService.createCanvas("attachments");
    CanvasThreadService.CanvasFirstSendCommand command =
        new CanvasThreadService.CanvasFirstSendCommand(
            commandId.toString(),
            new BranchSettings(
                null,
                "default-assistant",
                new ModelSelection("stub", "stub-model", "default"),
                List.of()),
            false,
            List.of(new TextMessageContent("inspect "), new AttachmentMessageContent(uploadUuid)));

    CanvasThreadService.CanvasFirstSendResult first =
        canvasThreadService.sendFirstMessage(canvas.id(), command);
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(first.threadId());
    UserMessageCommandPayload payload =
        assertInstanceOf(
            UserMessageCommandPayload.class, snapshot.queuedCommands().get(0).payload());
    AgentMessage message = payload.message();
    assertEquals(new TextMessageContent("inspect "), message.contents().get(0));
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, message.contents().get(1));
    assertEquals("canvas.txt", resource.name());
    UUID blobId = resource.blobId();

    assertEquals(0, count("storage_upload", "id", uploadUuid));
    assertEquals(1, count("harness_session_blob_ref", "blob_id", blobId));
    assertEquals(1L, storage.blobRefCount(blobId.toString()));
    assertTrue(s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)));

    CanvasThreadService.CanvasFirstSendResult replay =
        canvasThreadService.sendFirstMessage(canvas.id(), command);
    assertEquals(first.threadId(), replay.threadId());
    assertEquals(1, count("harness_thread_command", "thread_id", first.threadId()));
    assertEquals(1, count("harness_session_blob_ref", "blob_id", blobId));

    canvasCommandService.deleteCanvas(canvas.id());

    assertThrows(
        HarnessRuntimeNotFoundException.class, () -> runtime.getThreadSnapshot(first.threadId()));
    assertEquals(0, count("canvas_document", "id", canvas.id()));
    assertEquals(0, count("harness_session_blob_ref", "blob_id", blobId));
    assertEquals(0, count("storage_blob", "id", blobId));
    assertFalse(s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)));
  }

  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }
}
