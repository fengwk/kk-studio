package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.spi.ToolOutcomeMaterializationException;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.orchestration.UserMessageContentPreparer;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** READY upload 到 durable Tool history 的真实 PostgreSQL 原子转移契约。 */
@Import(StorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class GlobalStorageToolResultHistoryMaterializerIntegrationTest extends PostgresSpringTestSupport {

  private static final UUID SESSION_ID = new UUID(0L, 7001L);

  @Autowired private GlobalStorageToolResultHistoryMaterializer materializer;
  @Autowired private StorageUploadService uploadService;
  @Autowired private SessionBlobRefManager refManager;
  @Autowired private StorageBlobManager blobManager;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
    transaction = new TransactionTemplate(transactionManager);
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        SESSION_ID,
        "tool-history-test");
  }

  @Test
  void consumesReadyUploadWithoutObjectIo() {
    // Gateway 已完成对象准备；持有 Harness 事务的物化阶段只转移数据库引用。
    byte[] bytes = "hello\nworld".getBytes(StandardCharsets.UTF_8);
    StorageUploadService.StagedUpload staged = stage("result.txt", "text/plain", bytes);
    s3Storage.clearNetworkCalls();

    ToolResult stable =
        inTransaction(
            new ToolResult(
                "call-1",
                List.of(
                    new ResourceResultContent(
                        ref(staged), "hello\nworld", new TextArtifactMetadata(bytes.length, 2))),
                false,
                "{}"));

    ResourceResultContent content =
        assertInstanceOf(ResourceResultContent.class, stable.contents().getFirst());
    assertEquals(staged.blobId(), content.resource().sessionBlobId());
    assertEquals("result.txt", content.resource().name());
    assertEquals("hello\nworld", content.preview());
    assertEquals(bytes.length, content.resource().size());
    assertEquals(2L, content.textMetadata().totalLines());
    assertEquals(0, s3Storage.networkCalls().size());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            SESSION_ID,
            staged.blobId()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload"
                + " where id = ? and cleanup_requested_at is not null",
            Integer.class,
            staged.uploadId()));
  }

  @Test
  void laterInvalidResourceRollsBackEarlierOwnershipTransfer() {
    // 同一 ToolResult 的后置引用校验失败时，前置 retain/delete 必须与 Entry 一起整体回滚。
    StorageUploadService.StagedUpload first =
        stage("first.bin", "application/octet-stream", new byte[] {1});
    StorageUploadService.StagedUpload second =
        stage("second.bin", "application/octet-stream", new byte[] {2});
    ResourceRef invalidSecond =
        new ResourceRef(
            ResourceRef.blobUploadUri(second.uploadId()),
            second.mediaType(),
            second.filename(),
            second.sizeBytes(),
            "a".repeat(64));

    assertThrows(
        ToolOutcomeMaterializationException.class,
        () ->
            inTransaction(
                new ToolResult(
                    "call-1",
                    List.of(
                        new ResourceResultContent(ref(first)),
                        new ResourceResultContent(invalidSecond)),
                    false,
                    "{}")));

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ?",
            Integer.class,
            SESSION_ID));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload"
                + " where id in (?, ?) and cleanup_requested_at is not null",
            Integer.class,
            first.uploadId(),
            second.uploadId()));
  }

  @Test
  void rejectsTextReferenceToBlobNotAlreadyHeldBySession() {
    StorageUploadService.StagedUpload staged =
        stage("private.txt", "text/plain", "private".getBytes(StandardCharsets.UTF_8));
    String text = "resource://files/" + staged.blobId();

    assertThrows(
        ToolOutcomeMaterializationException.class,
        () ->
            inTransaction(
                new ToolResult("call-1", List.of(new TextResultContent(text)), false, "{}")));

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            SESSION_ID,
            staged.blobId()));
  }

  @Test
  void secondUsersSessionCannotClaimResourceHeldOnlyByFirstUsersSession() {
    TwoUserSessions users = createTwoUserSessions();
    StorageUploadService.StagedUpload staged =
        stage("first-user.txt", "text/plain", "private".getBytes(StandardCharsets.UTF_8));
    transaction.executeWithoutResult(status -> refManager.retainRef(SESSION_ID, staged.blobId()));

    assertThrows(
        ToolOutcomeMaterializationException.class,
        () ->
            inTransaction(
                users.secondSession(),
                new ToolResult(
                    "call-1",
                    List.of(new TextResultContent("resource://files/" + staged.blobId())),
                    false,
                    "{}")));

    assertEquals(1, countSessionRef(SESSION_ID, staged.blobId()));
    assertEquals(0, countSessionRef(users.secondSession(), staged.blobId()));
  }

  @Test
  void secondUsersMessageCannotReferenceBlobHeldOnlyByFirstUsersSession() {
    TwoUserSessions users = createTwoUserSessions();
    StorageUploadService.StagedUpload staged =
        stage("first-user.txt", "text/plain", "private".getBytes(StandardCharsets.UTF_8));
    transaction.executeWithoutResult(status -> refManager.retainRef(SESSION_ID, staged.blobId()));
    UserMessageContentPreparer preparer =
        new UserMessageContentPreparer(
            refManager,
            blobManager,
            (userId, sessionId, uploadId) -> {
              throw new AssertionError("message contains no upload");
            },
            () -> ImageInputTier.P720,
            () -> 2);
    NewThreadCommand command =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(new TextMessageContent("resource://files/" + staged.blobId())))),
            UUID.randomUUID());

    assertThrows(
        DomainException.class,
        () -> preparer.prepare(users.secondUser(), users.secondSession(), List.of(command), null));

    assertEquals(1, countSessionRef(SESSION_ID, staged.blobId()));
    assertEquals(0, countSessionRef(users.secondSession(), staged.blobId()));
  }

  @Test
  void requiresCallerTransaction() {
    StorageUploadService.StagedUpload staged =
        stage("result.bin", "application/octet-stream", new byte[] {1});

    assertThrows(
        IllegalTransactionStateException.class,
        () ->
            materializer.materialize(
                SESSION_ID,
                "tool",
                new ToolResult(
                    "call-1", List.of(new ResourceResultContent(ref(staged))), false, "{}")));
  }

  private StorageUploadService.StagedUpload stage(
      String filename, String mediaType, byte[] content) {
    return uploadService.stage(
        filename, mediaType, new ByteArrayInputStream(content), Math.max(1, content.length));
  }

  private static ResourceRef ref(StorageUploadService.StagedUpload staged) {
    return new ResourceRef(
        ResourceRef.blobUploadUri(staged.uploadId()),
        staged.mediaType(),
        staged.filename(),
        staged.sizeBytes(),
        staged.sha256());
  }

  private TwoUserSessions createTwoUserSessions() {
    UUID firstUser = UUID.randomUUID();
    UUID secondUser = UUID.randomUUID();
    UUID firstSpace = UUID.randomUUID();
    UUID secondSpace = UUID.randomUUID();
    UUID firstFolder = UUID.randomUUID();
    UUID secondFolder = UUID.randomUUID();
    UUID secondSession = UUID.randomUUID();
    jdbc.update(
        "insert into studio_user(id, username, password_hash, role) values (?, ?, 'x', 'USER'), (?, ?, 'x', 'USER')",
        firstUser,
        "first-" + firstUser.toString().substring(0, 8),
        secondUser,
        "second-" + secondUser.toString().substring(0, 8));
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        secondSession,
        "second-user-session");
    jdbc.update(
        "insert into chat_space(id, owner_user_id, title, title_key, create_request_hash)"
            + " values (?, ?, 'First', 'first', ?), (?, ?, 'Second', 'second', ?)",
        firstSpace,
        firstUser,
        "a".repeat(64),
        secondSpace,
        secondUser,
        "b".repeat(64));
    jdbc.update(
        "insert into workspace_folder(id, workspace_id, name, name_key, position)"
            + " values (?, ?, 'Root', 'root', 0), (?, ?, 'Root', 'root', 0)",
        firstFolder,
        firstSpace,
        secondFolder,
        secondSpace);
    jdbc.update(
        "insert into agent(name, config) values ('attachment-test', '{}'::jsonb) on conflict do nothing");
    jdbc.update(
        "insert into workspace_resource(id, workspace_id, folder_id, session_id, position, agent_name)"
            + " values (?, ?, ?, ?, 0, 'attachment-test'), (?, ?, ?, ?, 0, 'attachment-test')",
        SESSION_ID,
        firstSpace,
        firstFolder,
        SESSION_ID,
        secondSession,
        secondSpace,
        secondFolder,
        secondSession);
    assertEquals(firstUser, workspaceOwner(SESSION_ID));
    assertEquals(secondUser, workspaceOwner(secondSession));
    return new TwoUserSessions(firstUser, secondUser, secondSession);
  }

  private UUID workspaceOwner(UUID sessionId) {
    return jdbc.queryForObject(
        "select s.owner_user_id from workspace_resource r"
            + " join chat_space s on s.id = r.workspace_id where r.session_id = ?",
        UUID.class,
        sessionId);
  }

  private record TwoUserSessions(UUID firstUser, UUID secondUser, UUID secondSession) {}

  private int countSessionRef(UUID sessionId, UUID blobId) {
    return jdbc.queryForObject(
        "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
        Integer.class,
        sessionId,
        blobId);
  }

  private ToolResult inTransaction(ToolResult result) {
    return inTransaction(SESSION_ID, result);
  }

  private ToolResult inTransaction(UUID sessionId, ToolResult result) {
    return transaction.execute(status -> materializer.materialize(sessionId, "tool", result));
  }
}
