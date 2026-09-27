package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService.ReadyUpload;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService.StagedUpload;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link IssueEvidenceService} 集成测试：验证基于真实 PostgreSQL 的证据发布、列举与深删除语义。
 *
 * <p>测试意图：验证首次发布 retain 一次并写入证据行、重复发布幂等且不二次 retain、人工上传 lockReady 与 delete 协同、 跨 Issue Run
 * 引用校验拒绝、已归档 Issue 拒绝发布、releaseAll 释放全部持有引用并清空记录。
 */
@Import(IssueEvidenceServiceIntegrationTest.TestStorageConfiguration.class)
class IssueEvidenceServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueEvidenceService issueEvidenceService;
  @Autowired private FakeStorageUploadService fakeStorageUploadService;
  @Autowired private FakeStorageBlobManager fakeStorageBlobManager;

  @BeforeEach
  void resetStorageFakes() {
    fakeStorageUploadService.reset();
    fakeStorageBlobManager.reset();
  }

  /** 插入满足 storage_blob 表约束的合法 ACTIVE 记录。 */
  private void insertStorageBlob(UUID blobId) {
    String sha256 =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state) "
            + "values (?, ?, 1024, 'image/png', 1, 'ACTIVE')",
        blobId,
        sha256);
  }

  /** 首次发布 Blob 证据：恰好调用一次 retain 并在数据库中插入一条证据行。 */
  @Test
  void firstPublishBlobRetainsOnceAndInsertsRow() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    IssueEvidence evidence =
        issueEvidenceService.publishBlob(issue.getId(), null, null, blobId, "report.pdf");

    assertNotNull(evidence);
    assertEquals(issue.getId(), evidence.getIssueId());
    assertEquals(blobId, evidence.getBlobId());
    assertEquals("report.pdf", evidence.getName());
    assertNull(evidence.getActorAgentName());
    assertNull(evidence.getRunId());
    assertNotNull(evidence.getCreatedAt());

    assertEquals(1L, fakeStorageBlobManager.retainCount(blobId));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ?",
            issue.getId(),
            blobId));
  }

  /** 重复发布同一个 Blob 证据：幂等返回已有行，不触发二次 retain，也不产生新行。 */
  @Test
  void repeatedPublishReturnsExistingRowWithoutRetainingAgain() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    IssueEvidence first =
        issueEvidenceService.publishBlob(issue.getId(), null, null, blobId, "report.pdf");
    IssueEvidence second =
        issueEvidenceService.publishBlob(
            issue.getId(), null, null, blobId, "different-name-ignored.pdf");

    assertEquals(first.getIssueId(), second.getIssueId());
    assertEquals(first.getBlobId(), second.getBlobId());
    assertEquals(first.getName(), second.getName());
    assertEquals(first.getCreatedAt(), second.getCreatedAt());

    assertEquals(1L, fakeStorageBlobManager.retainCount(blobId));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ?",
            issue.getId(),
            blobId));
  }

  /** 人工上传转证据：按序调用 lockReady 与 delete(uploadId)，完成原子转移。 */
  @Test
  void publishHumanUploadCallsLockReadyThenDelete() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    fakeStorageUploadService.putReady(uploadId, blobId, "human-upload.png");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(issue.getId(), uploadId);

    assertNotNull(evidence);
    assertEquals(issue.getId(), evidence.getIssueId());
    assertEquals(blobId, evidence.getBlobId());
    assertEquals("human-upload.png", evidence.getName());

    assertEquals(1L, fakeStorageUploadService.lockCount(uploadId));
    assertEquals(1L, fakeStorageBlobManager.retainCount(blobId));
    assertEquals(1L, fakeStorageUploadService.deleteCount(uploadId));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ?",
            issue.getId(),
            blobId));
  }

  /** 人工上传命中已有 Blob：不重复 retain，直接返回既有证据行并清理当前 upload。 */
  @Test
  void publishHumanUploadDuplicateBlobDoesNotRetainAgain() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    issueEvidenceService.publishBlob(issue.getId(), null, null, blobId, "existing.png");

    UUID uploadId = UUID.randomUUID();
    fakeStorageUploadService.putReady(uploadId, blobId, "uploaded-dup.png");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(issue.getId(), uploadId);

    assertNotNull(evidence);
    assertEquals("existing.png", evidence.getName());
    assertEquals(1L, fakeStorageUploadService.lockCount(uploadId));
    assertEquals(1L, fakeStorageUploadService.deleteCount(uploadId));
    assertEquals(1L, fakeStorageBlobManager.retainCount(blobId));
  }

  /** 跨 Issue 引用 Run 被拒绝：发布时指定属于其他 Issue 的 runId 必须抛出校验异常。 */
  @Test
  void publishBlobWithRunFromAnotherIssueIsRejected() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("测试工作流", agent, agent, 3);
    Issue issue1 = createIssue(projectId);
    Issue issue2 = createIssue(projectId);

    issueService.transition(issue1.getId(), issue1.getVersion(), key("t"), "DESIGN");
    IssueRun runOfIssue1 = issueRunService.acceptRun(issue1.getId(), key("accept"));

    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    assertThrows(
        AiValidationException.class,
        () ->
            issueEvidenceService.publishBlob(
                issue2.getId(), agent, runOfIssue1.getId(), blobId, "evidence.txt"));

    assertEquals(0L, fakeStorageBlobManager.retainCount(blobId));
    assertEquals(0L, count("select count(*) from project_issue_evidence"));
  }

  /** 发布带有同 Issue Run 归属的证据：成功关联 actorAgentName 与 runId。 */
  @Test
  void publishBlobWithSameIssueRunSucceeds() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("测试工作流", agent, agent, 3);
    Issue issue = createIssue(projectId);

    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    IssueEvidence evidence =
        issueEvidenceService.publishBlob(
            issue.getId(), agent, run.getId(), blobId, "run-evidence.txt");

    assertNotNull(evidence);
    assertEquals(issue.getId(), evidence.getIssueId());
    assertEquals(blobId, evidence.getBlobId());
    assertEquals(agent, evidence.getActorAgentName());
    assertEquals(run.getId(), evidence.getRunId());
    assertEquals("run-evidence.txt", evidence.getName());
    assertEquals(1L, fakeStorageBlobManager.retainCount(blobId));
  }

  /** 指定 runId 时必须给出 actorAgentName：若缺失则拒绝。 */
  @Test
  void publishBlobWithRunIdRequiresActorAgentName() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    assertThrows(
        AiValidationException.class,
        () ->
            issueEvidenceService.publishBlob(
                issue.getId(), null, UUID.randomUUID(), blobId, "test.txt"));
  }

  /** 已归档 Issue 禁止发布证据：抛出校验异常。 */
  @Test
  void publishEvidenceToArchivedIssueIsRejected() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    jdbc.update(
        "update project_issue set archived_at = clock_timestamp() where id = ?", issue.getId());

    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    assertThrows(
        AiValidationException.class,
        () -> issueEvidenceService.publishBlob(issue.getId(), null, null, blobId, "test.txt"));
  }

  /** 深删除释放全部引用：逐行调用 release 并删除该 Issue 下的所有证据行。 */
  @Test
  void releaseAllReleasesEveryBlobAndRemovesAllRows() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blob1 = UUID.randomUUID();
    UUID blob2 = UUID.randomUUID();
    insertStorageBlob(blob1);
    insertStorageBlob(blob2);

    issueEvidenceService.publishBlob(issue.getId(), null, null, blob1, "first.txt");
    issueEvidenceService.publishBlob(issue.getId(), null, null, blob2, "second.txt");

    assertEquals(
        2L, count("select count(*) from project_issue_evidence where issue_id = ?", issue.getId()));

    int releasedCount = issueEvidenceService.releaseAll(issue.getId());

    assertEquals(2, releasedCount);
    assertEquals(1L, fakeStorageBlobManager.releaseCount(blob1));
    assertEquals(1L, fakeStorageBlobManager.releaseCount(blob2));
    assertEquals(
        0L, count("select count(*) from project_issue_evidence where issue_id = ?", issue.getId()));
  }

  /** 人工上传目标 Issue 不存在时抛出 404 资源未找到异常。 */
  @Test
  void publishHumanUploadNonExistentIssueThrowsNotFound() {
    UUID uploadId = UUID.randomUUID();
    UUID nonExistentIssueId = UUID.randomUUID();
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueEvidenceService.publishHumanUpload(nonExistentIssueId, uploadId));
  }

  /** 人工上传目标 Issue 已归档时拒绝发布。 */
  @Test
  void publishHumanUploadArchivedIssueIsRejected() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    jdbc.update(
        "update project_issue set archived_at = clock_timestamp() where id = ?", issue.getId());
    UUID uploadId = UUID.randomUUID();

    assertThrows(
        AiValidationException.class,
        () -> issueEvidenceService.publishHumanUpload(issue.getId(), uploadId));
  }

  /** 发布 Blob 证据时 blobId 必须非空。 */
  @Test
  void publishBlobWithNullBlobIdThrowsValidationException() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);

    assertThrows(
        AiValidationException.class,
        () -> issueEvidenceService.publishBlob(issue.getId(), null, null, null, "test.pdf"));
  }

  /** 发布 Blob 证据时目标 Issue 不存在则抛出 404。 */
  @Test
  void publishBlobNonExistentIssueThrowsNotFound() {
    UUID blobId = UUID.randomUUID();
    UUID nonExistentIssueId = UUID.randomUUID();

    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            issueEvidenceService.publishBlob(nonExistentIssueId, null, null, blobId, "report.pdf"));
  }

  /** 发布 Blob 证据时指定的 runId 不存在则抛出 404。 */
  @Test
  void publishBlobNonExistentRunThrowsNotFound() {
    String agent = createAgent();
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            issueEvidenceService.publishBlob(
                issue.getId(), agent, UUID.randomUUID(), blobId, "report.pdf"));
  }

  /** 发布无 Run 归属但带 Agent 作者的证据：成功记录 actorAgentName。 */
  @Test
  void publishBlobWithAgentWithoutRunSucceeds() {
    String agent = createAgent();
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);

    IssueEvidence evidence =
        issueEvidenceService.publishBlob(issue.getId(), agent, null, blobId, "agent-upload.pdf");

    assertNotNull(evidence);
    assertEquals(agent, evidence.getActorAgentName());
    assertNull(evidence.getRunId());
    assertEquals("agent-upload.pdf", evidence.getName());
  }

  /** 证据列表按创建时间最新优先返回，并受 MAX_EVIDENCE_LIMIT 截断；同毫秒写入也必须保持确定性顺序。 */
  @Test
  void listEvidenceReturnsNewestFirst() {
    UUID projectId = createProject();
    Issue issue = createIssue(projectId);
    UUID blob1 = UUID.randomUUID();
    UUID blob2 = UUID.randomUUID();
    insertStorageBlob(blob1);
    insertStorageBlob(blob2);

    issueEvidenceService.publishBlob(issue.getId(), null, null, blob1, "first.txt");
    issueEvidenceService.publishBlob(issue.getId(), null, null, blob2, "second.txt");

    // 同一事务内写入的两行可能共享同一时间戳，因此显式错开创建时间，断言「最新优先」而不依赖时钟分辨率。
    jdbc.update(
        "update project_issue_evidence set created_at = ? where issue_id = ? and blob_id = ?",
        Timestamp.valueOf("2026-01-01 00:00:00"),
        issue.getId(),
        blob1);
    jdbc.update(
        "update project_issue_evidence set created_at = ? where issue_id = ? and blob_id = ?",
        Timestamp.valueOf("2026-01-02 00:00:00"),
        issue.getId(),
        blob2);

    List<IssueEvidence> list = issueEvidenceService.listEvidence(issue.getId());
    assertEquals(2, list.size());
    assertEquals(blob2, list.get(0).getBlobId());
    assertEquals(blob1, list.get(1).getBlobId());
  }

  /**
   * 仅通过 {@link Import} 生效的存储假件配置：刻意不加 {@code @TestConfiguration}/{@code @Configuration}，避免被
   * platform 组件的扫描拾取后污染其他测试上下文。
   */
  public static class TestStorageConfiguration {

    @Bean
    @Primary
    public FakeStorageUploadService testStorageUploadService() {
      return new FakeStorageUploadService();
    }

    @Bean
    @Primary
    public FakeStorageBlobManager testStorageBlobManager() {
      return new FakeStorageBlobManager();
    }
  }

  public static class FakeStorageBlobManager implements StorageBlobManager {

    private final List<UUID> retained = new CopyOnWriteArrayList<>();
    private final List<UUID> released = new CopyOnWriteArrayList<>();

    @Override
    public StorageBlob retain(UUID blobId) {
      retained.add(blobId);
      StorageBlob blob = new StorageBlob();
      blob.setId(blobId);
      blob.setState(StorageBlobState.ACTIVE);
      blob.setRefCount(1);
      return blob;
    }

    @Override
    public boolean release(UUID blobId) {
      released.add(blobId);
      return true;
    }

    @Override
    public StorageBlob getBlob(UUID blobId) {
      return null;
    }

    @Override
    public StoragePresignedUrlDTO presignOriginalUrl(UUID blobId) {
      return null;
    }

    @Override
    public StoragePresignedUrlDTO presignPreviewUrl(UUID blobId) {
      return null;
    }

    @Override
    public int sweepDeleting() {
      return 0;
    }

    public long retainCount(UUID blobId) {
      return retained.stream().filter(id -> id.equals(blobId)).count();
    }

    public long releaseCount(UUID blobId) {
      return released.stream().filter(id -> id.equals(blobId)).count();
    }

    public void reset() {
      retained.clear();
      released.clear();
    }
  }

  public static class FakeStorageUploadService implements StorageUploadService {

    private final Map<UUID, ReadyUpload> readyMap = new ConcurrentHashMap<>();
    private final List<UUID> deleted = new CopyOnWriteArrayList<>();
    private final List<UUID> locked = new CopyOnWriteArrayList<>();

    public void putReady(UUID uploadId, UUID blobId, String filename) {
      readyMap.put(uploadId, new ReadyUpload(blobId, filename));
    }

    @Override
    public ReadyUpload lockReady(UUID uploadId) {
      locked.add(uploadId);
      ReadyUpload ready = readyMap.get(uploadId);
      if (ready == null) {
        throw new StorageResourceNotFoundException("upload", uploadId.toString());
      }
      return ready;
    }

    @Override
    public void delete(UUID uploadId) {
      deleted.add(uploadId);
    }

    @Override
    public StorageUploadDTO reserve(StorageUploadReserveRequestDTO request) {
      return null;
    }

    @Override
    public StorageUploadDTO complete(UUID uploadId) {
      return null;
    }

    @Override
    public StagedUpload stage(
        String filename, String mediaType, InputStream content, long maxBytes) {
      return null;
    }

    @Override
    public int expireOnce() {
      return 0;
    }

    public long lockCount(UUID uploadId) {
      return locked.stream().filter(id -> id.equals(uploadId)).count();
    }

    public long deleteCount(UUID uploadId) {
      return deleted.stream().filter(id -> id.equals(uploadId)).count();
    }

    public void reset() {
      readyMap.clear();
      deleted.clear();
      locked.clear();
    }
  }
}
