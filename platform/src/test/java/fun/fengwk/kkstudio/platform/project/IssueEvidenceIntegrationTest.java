package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidenceOrigin;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRole;
import fun.fengwk.kkstudio.platform.project.tool.ProjectRoleToolService;
import fun.fengwk.kkstudio.platform.project.tool.ProjectThreadOwnerContext;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageIntegrationSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issue 公开证据的 PostgreSQL 集成测试（真实事务 + 真实 Blob 引用账本 + 内存 S3 假件）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>只有「执行者最终答复明确引用规范 {@code kkstudio:/resources/<blobId>} 且来源 Run 的 Session 在提交时确实持有该引用」的产物 成为
 *       Issue 公开证据；Issue 自己的引用与各 Session 引用独立计数，来源 Session 不重复计数；
 *   <li>引用其他 Agent 私有资源或不存在的 URI 时拒绝整个提交：不产生证据、不 retain、Run 与 Issue 状态不变；
 *   <li>同一证据跨 Run 重复发布不新增证据行、不重复 retain；
 *   <li>人工上传经 {@code lockReady -> retain Issue 引用 -> delete upload} 原子转移引用，展示名取自上传行，并在有序事实流留痕；
 *   <li>公开读取面以规范 URI 暴露有界证据窗口（最新在前），审查者用自己 Session 的授权读取。
 * </ul>
 */
@Import({StorageS3TestConfiguration.class})
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class IssueEvidenceIntegrationTest extends ProjectTestSupport {

  private static final long RUN_DEADLINE_SECONDS = 3600L;
  private static final int REVIEW_THRESHOLD = 3;
  private static final int MAX_CONTINUATIONS = 5;

  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueEvidenceService issueEvidenceService;
  @Autowired private IssueAgentSessionRepository issueAgentSessionRepository;
  @Autowired private IssueActivityRepository issueActivityRepository;
  @Autowired private ProjectRoleToolService projectRoleToolService;
  @Autowired private SessionBlobRefManager refManager;
  @Autowired private StorageUploadService uploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private StorageIntegrationSupport storage;

  @BeforeEach
  void resetStorage() {
    s3Storage.clear();
    storage = new StorageIntegrationSupport(uploadService, s3Storage, jdbcTemplate);
    tx = new TransactionTemplate(transactionManager);
  }

  @Test
  void executorFinalPublishesOwnedResourceAndGrantsBoundParticipants() {
    Fixture fixture = fixture();
    UUID executorSession = bindAgentSession(fixture.issueId(), fixture.executor());
    UUID reviewerSession = bindAgentSession(fixture.issueId(), fixture.reviewer());
    UUID blobId = uploadBlobOwnedBySession(executorSession, "report.txt", "text/plain", "evidence");

    // 发布前账本里只有来源 Session 的引用：证据不得提前公开
    assertEquals(1L, refCount(blobId));
    assertEquals(0, evidenceCount(fixture.issueId()));

    IssueRun run = startExecutorRun(fixture, "Implemented the fix. Report: " + uri(blobId));

    // Issue 自有引用 +1；另一个已绑定参与者 Session 各持有自己的引用；来源 Session 不重复计数
    assertEquals(3L, refCount(blobId));
    assertTrue(refManager.contains(executorSession, blobId));
    assertTrue(refManager.contains(reviewerSession, blobId));
    assertEquals(2, sessionRefCount(blobId));

    List<IssueEvidence> evidence = issueEvidenceService.listEvidence(fixture.issueId());
    assertEquals(1, evidence.size());
    assertEquals(blobId, evidence.get(0).getBlobId());
    assertEquals(IssueEvidenceOrigin.EXECUTOR, evidence.get(0).getOrigin());
    assertEquals(run.getId(), evidence.get(0).getRunId());
    assertNull(evidence.get(0).getName(), "executor citations carry no authoritative filename");
    assertNotNull(evidence.get(0).getCreatedAt());

    assertEquals(IssueRunStatus.COMPLETED, issueRunService.getRun(run.getId()).getStatus());
    assertEquals(IssueStatus.IN_REVIEW, issueService.getIssue(fixture.issueId()).getStatus());
  }

  @Test
  void reviewerReadsPublishedEvidenceThroughItsOwnSessionGrant() {
    Fixture fixture = fixture();
    UUID executorSession = bindAgentSession(fixture.issueId(), fixture.executor());
    UUID reviewerSession = bindAgentSession(fixture.issueId(), fixture.reviewer());
    UUID blobId = uploadBlobOwnedBySession(executorSession, "chart.bin", "image/png", "chart");
    IssueRun executorRun = startExecutorRun(fixture, "See " + uri(blobId));

    // 审查者使用自己的 Session/分支执行，只能通过已发布证据授权读取，而不是共享执行者 Session
    IssueRun reviewerRun =
        issueRunService.startReviewerRun(
            fixture.issueId(),
            fixture.reviewer(),
            Instant.now().plusSeconds(RUN_DEADLINE_SECONDS),
            MAX_CONTINUATIONS);
    assertTrue(refManager.contains(reviewerSession, blobId));

    Map<String, Object> data =
        projectRoleToolService.issueRead(
            new ProjectThreadOwnerContext(
                ProjectRole.REVIEWER,
                fixture.projectId(),
                fixture.issueId(),
                reviewerRun.getId(),
                fixture.reviewer()),
            null,
            null,
            null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> evidence = (List<Map<String, Object>>) data.get("evidence");
    assertEquals(1, evidence.size());
    assertEquals(uri(blobId), evidence.get(0).get("uri"));
    assertEquals("EXECUTOR", evidence.get(0).get("origin"));
    assertEquals(
        executorRun.getId().toString(),
        evidence.get(0).get("run_id"),
        "published evidence keeps溯源到发布它的执行 Run，而不是读取者的 Run");
  }

  @Test
  void privateOrUnknownUriRejectsSubmissionWithoutPublishingAnything() {
    Fixture fixture = fixture();
    String otherAgent = createTestAgent();
    UUID otherSession = bindAgentSession(fixture.issueId(), otherAgent);
    bindAgentSession(fixture.issueId(), fixture.executor());
    UUID foreignBlobId =
        uploadBlobOwnedBySession(otherSession, "private.txt", "text/plain", "private");
    UUID unknownBlobId = UUID.randomUUID();

    IssueRun run =
        issueRunService.startExecutorRun(
            fixture.issueId(),
            fixture.executor(),
            Instant.now().plusSeconds(RUN_DEADLINE_SECONDS),
            MAX_CONTINUATIONS);

    // 其他 Agent 的私有资源：来源 Session 不持有该引用，必须拒绝整个提交而不是静默公开
    AiValidationException foreign =
        assertThrows(
            AiValidationException.class, () -> submit(run, "Leaked " + uri(foreignBlobId)));
    assertTrue(foreign.getMessage().contains("does not hold"));

    // 完全不存在的 URI 同样拒绝
    assertThrows(AiValidationException.class, () -> submit(run, "Guess " + uri(unknownBlobId)));

    assertEquals(0, evidenceCount(fixture.issueId()));
    assertEquals(
        1L, refCount(foreignBlobId), "refused publication must not retain the foreign blob");
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
    assertEquals(IssueStatus.IN_PROGRESS, issueService.getIssue(fixture.issueId()).getStatus());
  }

  @Test
  void repeatedPublicationAcrossRunsDoesNotDoubleRetain() {
    Fixture fixture = fixture();
    UUID executorSession = bindAgentSession(fixture.issueId(), fixture.executor());
    UUID blobId = uploadBlobOwnedBySession(executorSession, "artifact.txt", "text/plain", "body");
    String summary = "Delivered " + uri(blobId);

    startExecutorRun(fixture, summary);
    assertEquals(1, evidenceCount(fixture.issueId()));
    assertEquals(2L, refCount(blobId), "issue owns one reference on top of the session reference");

    // 打回后同一 Agent 复用 Session 再次执行，并再次引用同一产物
    IssueRun reviewRun =
        issueRunService.startReviewerRun(
            fixture.issueId(),
            fixture.reviewer(),
            Instant.now().plusSeconds(RUN_DEADLINE_SECONDS),
            MAX_CONTINUATIONS);
    issueRunService.reviewByAgent(
        reviewRun.getId(),
        fixture.reviewer(),
        "review:1",
        ReviewDecision.REQUEST_CHANGES,
        "Needs another pass");
    assertEquals(IssueStatus.TODO, issueService.getIssue(fixture.issueId()).getStatus());

    startExecutorRun(fixture, summary + " (resubmitted)");

    assertEquals(1, evidenceCount(fixture.issueId()), "same issue/blob must stay a single row");
    assertEquals(2L, refCount(blobId), "re-publishing must not retain the issue reference twice");
    assertEquals(1, sessionRefCount(blobId));
  }

  @Test
  void humanUploadPublishesEvidenceReleasesUploadOwnerAndRecordsFact() {
    Fixture fixture = fixture();
    UUID boundSession = bindAgentSession(fixture.issueId(), fixture.executor());
    String filename = "acceptance.txt";
    UUID uploadId = readyUpload(filename, "text/plain", "human evidence");
    UUID blobId = uploadedBlobId(uploadId);
    assertEquals(1L, refCount(blobId), "the ready upload owns the only reference");

    IssueEvidence evidence = issueEvidenceService.publishHumanUpload(fixture.issueId(), uploadId);

    assertEquals(blobId, evidence.getBlobId());
    assertEquals(IssueEvidenceOrigin.HUMAN, evidence.getOrigin());
    assertEquals(filename, evidence.getName(), "name comes from the upload row, not the client");
    assertNull(evidence.getRunId());
    // Issue 持有自己的引用，已绑定参与者持有自己的引用，upload owner 在同事务内释放
    assertEquals(2L, refCount(blobId));
    assertTrue(refManager.contains(boundSession, blobId));
    assertEquals(
        1,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and origin = 'HUMAN'",
            fixture.issueId()));
    assertNotNull(
        jdbcTemplate.queryForObject(
            "select cleanup_requested_at from storage_upload where id = ?",
            Instant.class,
            uploadId),
        "consumed upload must be marked for cleanup");

    IssueActivity activity =
        issueActivityRepository.findByIssueIdAndIdempotencyKey(
            fixture.issueId(), "evidence:" + blobId);
    assertNotNull(activity, "human publication must leave an ordered fact");
    assertEquals(IssueActivityKind.COMMENT, activity.getKind());
    assertEquals(IssueActivityActorType.HUMAN, activity.getActorType());
    assertTrue(activity.getBody().contains(uri(blobId)));

    // 同一内容再次上传（去重到同一 blob）后重新发布：不新增证据行、不重复 retain
    UUID secondUploadId = readyUpload(filename, "text/plain", "human evidence");
    assertEquals(blobId, uploadedBlobId(secondUploadId));
    issueEvidenceService.publishHumanUpload(fixture.issueId(), secondUploadId);
    assertEquals(1, evidenceCount(fixture.issueId()));
    assertEquals(2L, refCount(blobId));
    assertEquals(1, sessionRefCount(blobId));
  }

  @Test
  void publishedEvidenceReadIsBoundedAndNewestFirst() {
    Fixture fixture = fixture();
    UUID boundSession = bindAgentSession(fixture.issueId(), fixture.executor());
    int seeded = IssueEvidenceService.MAX_EVIDENCE_LIMIT + 5;
    for (int i = 0; i < seeded; i++) {
      UUID blobId = seedActiveBlob(i);
      tx.executeWithoutResult(status -> refManager.retainRef(boundSession, blobId));
      jdbcTemplate.update(
          "insert into project_issue_evidence (issue_id, blob_id, origin, run_id, name, created_at)"
              + " values (?, ?, 'HUMAN', null, ?, clock_timestamp() - (? || ' seconds')::interval)",
          fixture.issueId(),
          blobId,
          "seed-" + i,
          i);
    }

    List<IssueEvidence> evidence = issueEvidenceService.listEvidence(fixture.issueId());

    assertEquals(IssueEvidenceService.MAX_EVIDENCE_LIMIT, evidence.size());
    assertEquals("seed-0", evidence.get(0).getName(), "newest publication must come first");
    assertEquals(
        "seed-" + (IssueEvidenceService.MAX_EVIDENCE_LIMIT - 1),
        evidence.get(evidence.size() - 1).getName());
  }

  private Fixture fixture() {
    String executor = createTestAgent();
    String reviewer = createTestAgent();
    Project project =
        projectService.createProject("Evidence project", "Description", true, REVIEW_THRESHOLD);
    Issue issue =
        issueService.createIssue(
            project.getId(),
            "Publish evidence",
            "Description",
            executor,
            reviewer,
            IssueStatus.TODO);
    return new Fixture(project.getId(), issue.getId(), executor, reviewer);
  }

  /** 建立真实归属行与 Harness Session：{@code session_blob_ref} 与归属表都要求 Session 行先存在。 */
  private UUID bindAgentSession(UUID issueId, String agentName) {
    UUID sessionId = createHarnessSession();
    UUID threadId = createHarnessThread(sessionId);
    issueAgentSessionRepository.bindOrGet(
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .agentName(agentName)
            .sessionId(sessionId)
            .threadId(threadId)
            .build());
    return sessionId;
  }

  /** 模拟生产消费链：新上传 -> 唯一引用转移给 Session -> 释放 upload owner。 */
  private UUID uploadBlobOwnedBySession(
      UUID sessionId, String filename, String mediaType, String content) {
    UUID uploadId = readyUpload(filename, mediaType, content);
    UUID blobId = uploadedBlobId(uploadId);
    tx.executeWithoutResult(status -> refManager.retainRef(sessionId, blobId));
    uploadService.delete(uploadId);
    return blobId;
  }

  private UUID readyUpload(String filename, String mediaType, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(filename, mediaType, bytes.length, storage.sha256Hex(bytes));
    storage.putUploadContent(pending.getId(), bytes, mediaType);
    uploadService.complete(UUID.fromString(pending.getId()));
    return UUID.fromString(pending.getId());
  }

  private UUID uploadedBlobId(UUID uploadId) {
    return UUID.fromString(
        jdbcTemplate.queryForObject(
            "select blob_id from storage_upload where id = ?", String.class, uploadId));
  }

  private IssueRun startExecutorRun(Fixture fixture, String summary) {
    IssueRun run =
        issueRunService.startExecutorRun(
            fixture.issueId(),
            fixture.executor(),
            Instant.now().plusSeconds(RUN_DEADLINE_SECONDS),
            MAX_CONTINUATIONS);
    submit(run, summary);
    return run;
  }

  private void submit(IssueRun run, String summary) {
    issueRunService.completeExecutorRun(run.getId(), "submit:" + run.getId(), summary, null);
  }

  /** 直接种入一个 ACTIVE blob 行（ref_count = 1），用于覆盖有界读取面的排序与上限。 */
  private UUID seedActiveBlob(int index) {
    UUID blobId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, ?, 'text/plain', 1, 'ACTIVE')",
        blobId,
        String.format("%064x", index),
        index);
    return blobId;
  }

  private static String uri(UUID blobId) {
    return "kkstudio:/resources/" + blobId;
  }

  private long refCount(UUID blobId) {
    return storage.blobRefCount(blobId.toString());
  }

  private int sessionRefCount(UUID blobId) {
    return count("select count(*) from session_blob_ref where blob_id = ?", blobId);
  }

  private int evidenceCount(UUID issueId) {
    return count("select count(*) from project_issue_evidence where issue_id = ?", issueId);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
    return value != null ? value : 0;
  }

  private record Fixture(UUID projectId, UUID issueId, String executor, String reviewer) {}
}
