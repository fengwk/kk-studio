package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueAgentSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issue 公开证据在 web 组合根的生命周期集成测试（真实 PostgreSQL + 真实引导/深删除 + 内存 S3 假件）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li><b>发布后再引导</b>：审查者 Session 晚于发布创建时，引导在同一事务内把已发布证据的指定 Blob 引用幂等授予它，跨 Agent 读取得以成立； exact
 *       replay 不重复计数，且没有通配可见性——未获授权的其它参与者 Session 读不到该资源；
 *   <li><b>解码形态与可用性一致</b>：授权 Session 用自己的 Thread 经平台资源读取入口按规范 {@code kkstudio:/resources/<blobId>}
 *       形态能真正读到内容；未引用该 Blob 的 Session 被确定性拒绝；
 *   <li><b>授权失败整体回滚</b>：Blob 不再可用（DELETING）时引导失败，不留下已接受命令却看不到公开证据的 Session；
 *   <li><b>Session 删除不回收 Issue 证据</b>：深删除只释放该 Session 自己的引用，Issue 证据行与 Issue 持有引用保留，新 Session
 *       可再次获授权读取；
 *   <li><b>Project 删除释放全部引用</b>：证据行消失、Issue 与 Session 引用都释放、Blob 归零切 DELETING，且删除顺序满足外键约束。
 * </ul>
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
class IssueEvidenceLifecycleIntegrationTest extends WebPostgresTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();
  private static final String EVIDENCE_CONTENT = "published evidence body";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueEvidenceService issueEvidenceService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ProjectHarnessSessionBootstrapService bootstrapService;
  @Autowired private SessionDeletionOrchestrator sessionDeletionOrchestrator;
  @Autowired private SessionBlobRefManager refManager;
  @Autowired private PlatformResourceContentReader resourceContentReader;
  @Autowired private StorageUploadService uploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @MockitoBean private StorageMaintenance storageMaintenance;

  private IssueEvidenceTestSupport support;
  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
    support = new IssueEvidenceTestSupport(uploadService, s3Storage, jdbc);
    tx = new TransactionTemplate(transactionManager);
  }

  @Test
  void reviewerSessionBootstrappedAfterPublicationIsGrantedAndCanRead() {
    String executor = createTestAgent();
    String reviewer = createTestAgent();
    Fixture fixture = createIssue(executor, reviewer);
    UUID blobId = publishHumanEvidence(fixture.issueId(), "design.txt");
    assertEquals(1L, support.refCount(blobId), "publication happens before any session exists");

    Session reviewerSession = bootstrap(fixture.issueId(), reviewer);

    // 引导在同一事务内向新 Session 幂等授予「指定」已发布引用，Issue 与 Session 引用各自独立计数
    assertTrue(refManager.contains(reviewerSession.sessionId(), blobId));
    assertEquals(2L, support.refCount(blobId));
    assertEquals(1, support.count("session_blob_ref", "session_id", reviewerSession.sessionId()));

    // 规范 URI 形态在 Blob 实际可用时为活动形态：授权 Session 用自己的 Thread 能真正读到内容
    String text = readResource(reviewerSession.threadId(), blobId);
    assertTrue(text.contains(EVIDENCE_CONTENT), "published evidence must stay readable: " + text);

    // exact replay 幂等：不重复写入引用，也不重复计数
    AcceptedCommands replayed =
        bootstrapService.bootstrapIssueAgentSession(
            new BootstrapIssueAgentSessionRequest(
                fixture.issueId(),
                reviewer,
                reviewerSession.sessionId(),
                reviewerSession.threadId(),
                reviewerSession.idempotencyKey(),
                "Please review"));
    assertTrue(replayed.replayed());
    assertEquals(1, support.count("session_blob_ref", "session_id", reviewerSession.sessionId()));
    assertEquals(2L, support.refCount(blobId));

    // 无通配可见性：同项目另一个 Issue 的参与者 Session 不因知道 URI 而获得读取权限
    Fixture other = createIssue(createTestAgent(), createTestAgent());
    Session strangerSession = bootstrap(other.issueId(), other.executor());
    PlatformReadException rejected =
        assertThrows(
            PlatformReadException.class, () -> readResource(strangerSession.threadId(), blobId));
    assertTrue(rejected.getMessage().contains("not referenced by this session"));
  }

  @Test
  void bothParticipantsAreGrantedPublicEvidenceRegardlessOfBindingOrder() {
    String executor = createTestAgent();
    String reviewer = createTestAgent();
    Fixture fixture = createIssue(executor, reviewer);

    // 执行者在发布前已绑定：发布时即时授予
    Session executorSession = bootstrap(fixture.issueId(), executor);
    UUID blobId = publishHumanEvidence(fixture.issueId(), "shared.txt");
    assertTrue(refManager.contains(executorSession.sessionId(), blobId));

    // 审查者在发布后引导：引导时补齐授予
    Session reviewerSession = bootstrap(fixture.issueId(), reviewer);
    assertTrue(refManager.contains(reviewerSession.sessionId(), blobId));

    // Issue + 两个参与者 Session 各自持有引用
    assertEquals(3L, support.refCount(blobId));
    assertEquals(1, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertTrue(readResource(reviewerSession.threadId(), blobId).contains(EVIDENCE_CONTENT));
  }

  @Test
  void executorPublishedEvidenceIsGrantedToReviewerSessionCreatedAfterPublication() {
    String executor = createTestAgent();
    String reviewer = createTestAgent();
    Fixture fixture = createIssue(executor, reviewer);
    Session executorSession = bootstrap(fixture.issueId(), executor);

    // 来源 Run 的 Session 真实持有该引用（工具结果授权），另一份未引用的附件必须保持私有
    UUID citedBlobId =
        uploadBlobOwnedBySession(executorSession.sessionId(), "report.txt", EVIDENCE_CONTENT);
    // 不同内容 -> 不同 blob：证明未引用的附件不会被发布顺带公开
    UUID privateBlobId =
        uploadBlobOwnedBySession(executorSession.sessionId(), "draft.txt", "private draft body");

    IssueRun run =
        issueRunService.startExecutorRun(
            fixture.issueId(), executor, Instant.now().plusSeconds(3600), 5);
    issueRunService.completeExecutorRun(
        run.getId(),
        "submit:1",
        "Implemented the change. Evidence: kkstudio:/resources/" + citedBlobId,
        null);

    // 只有 final 明确引用且来源 Session 持有的产物公开
    assertEquals(1, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertEquals(
        "EXECUTOR",
        jdbc.queryForObject(
            "select origin from project_issue_evidence where issue_id = ? and blob_id = ?",
            String.class,
            fixture.issueId(),
            citedBlobId));
    assertEquals(
        run.getId(),
        jdbc.queryForObject(
            "select run_id from project_issue_evidence where issue_id = ? and blob_id = ?",
            UUID.class,
            fixture.issueId(),
            citedBlobId));

    // 审查者的 Session 晚于发布才创建：引导补齐授权，规范 URI 形态随即可用
    Session reviewerSession = bootstrap(fixture.issueId(), reviewer);
    assertTrue(refManager.contains(reviewerSession.sessionId(), citedBlobId));
    assertTrue(
        readResource(reviewerSession.threadId(), citedBlobId).contains(EVIDENCE_CONTENT),
        "reviewer bootstrapped after publication must read the published evidence");

    // 未发布的同 Session 附件仍不可见：发布不扩大授权面
    assertFalse(refManager.contains(reviewerSession.sessionId(), privateBlobId));
    PlatformReadException rejected =
        assertThrows(
            PlatformReadException.class,
            () -> readResource(reviewerSession.threadId(), privateBlobId));
    assertTrue(rejected.getMessage().contains("not referenced by this session"));
  }

  @Test
  void bootstrapRollsBackWhenPublishedEvidenceBlobIsNoLongerAvailable() {
    String executor = createTestAgent();
    String reviewer = createTestAgent();
    Fixture fixture = createIssue(executor, reviewer);
    UUID blobId = publishHumanEvidence(fixture.issueId(), "vanished.txt");
    // 模拟证据指向的 Blob 已不可用：引导必须整体失败，绝不留下已接受命令却看不到公开证据的 Session
    jdbc.update("update storage_blob set ref_count = 0, state = 'DELETING' where id = ?", blobId);

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    assertThrows(
        StorageResourceNotFoundException.class,
        () ->
            bootstrapService.bootstrapIssueAgentSession(
                new BootstrapIssueAgentSessionRequest(
                    fixture.issueId(),
                    reviewer,
                    sessionId,
                    threadId,
                    UUID.randomUUID(),
                    "Review the evidence")));

    assertEquals(0, support.count("harness_session", "id", sessionId));
    assertEquals(0, support.count("harness_thread", "id", threadId));
    assertEquals(0, support.count("harness_entry", "session_id", sessionId));
    assertEquals(0, support.count("session_owner", "session_id", sessionId));
    assertEquals(0, support.count("project_issue_agent_session", "issue_id", fixture.issueId()));
    assertEquals(0, support.count("session_blob_ref", "blob_id", blobId));
    assertEquals(1, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
  }

  @Test
  void deletingSessionReleasesOnlyItsOwnReferenceAndKeepsIssueEvidence() {
    String executor = createTestAgent();
    Fixture fixture = createIssue(executor, null);
    Session session = bootstrap(fixture.issueId(), executor);
    UUID blobId = publishHumanEvidence(fixture.issueId(), "keep.txt");
    assertEquals(2L, support.refCount(blobId), "issue + executor session hold one reference each");

    sessionDeletionOrchestrator.deleteSessionsByOwner(
        new OwnerRef(OwnerType.ISSUE_AGENT_SESSION, session.agentSessionId()));

    // Session 侧引用随 Session 消失，Issue 证据与 Issue 持有的引用保留，Blob 仍可用
    assertEquals(0, support.count("session_blob_ref", "session_id", session.sessionId()));
    assertEquals(0, support.count("project_issue_agent_session", "issue_id", fixture.issueId()));
    assertEquals(1, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertEquals(1L, support.refCount(blobId));
    assertEquals("ACTIVE", support.blobState(blobId));

    // 同一 Agent 的新 Session 再次获授权即可读取：证据不因 Session 删除而永久不可读
    Session rebound = bootstrap(fixture.issueId(), executor);
    assertTrue(refManager.contains(rebound.sessionId(), blobId));
    assertEquals(2L, support.refCount(blobId));
    assertTrue(readResource(rebound.threadId(), blobId).contains(EVIDENCE_CONTENT));
  }

  @Test
  void deletingProjectReleasesIssueAndSessionReferencesInForeignKeyOrder() {
    String executor = createTestAgent();
    String reviewer = createTestAgent();
    Fixture fixture = createIssue(executor, reviewer);
    Session executorSession = bootstrap(fixture.issueId(), executor);
    Session reviewerSession = bootstrap(fixture.issueId(), reviewer);
    UUID blobId = publishHumanEvidence(fixture.issueId(), "project.txt");
    assertEquals(3L, support.refCount(blobId));
    assertTrue(refManager.contains(executorSession.sessionId(), blobId));
    assertTrue(refManager.contains(reviewerSession.sessionId(), blobId));

    projectService.deleteProject(
        fixture.projectId(), projectService.getProject(fixture.projectId()).getVersion());

    // 证据行消失、Issue 与 Session 引用全部释放、Blob 归零切 DELETING：整个删除过程无外键冲突
    assertEquals(0, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertEquals(0, support.count("session_blob_ref", "blob_id", blobId));
    assertEquals(0, support.count("project_issue", "id", fixture.issueId()));
    assertEquals(0, support.count("project", "id", fixture.projectId()));
    assertEquals(0L, support.refCount(blobId));
    assertEquals("DELETING", support.blobState(blobId));
    assertFalse(refManager.contains(executorSession.sessionId(), blobId));
    assertFalse(refManager.contains(reviewerSession.sessionId(), blobId));
  }

  private String readResource(UUID threadId, UUID blobId) {
    return resourceContentReader.readResourceText(
        threadId, blobId, null, null, null, "kkstudio:/resources/" + blobId);
  }

  /** 模拟生产消费链：新上传 -> 单一引用归 Session -> 释放 upload owner。 */
  private UUID uploadBlobOwnedBySession(UUID sessionId, String filename, String content) {
    UUID uploadId = support.readyUpload(filename, content);
    UUID blobId = support.blobIdOf(uploadId);
    tx.executeWithoutResult(status -> refManager.retainRef(sessionId, blobId));
    uploadService.delete(uploadId);
    return blobId;
  }

  private UUID publishHumanEvidence(UUID issueId, String filename) {
    UUID uploadId = support.readyUpload(filename, EVIDENCE_CONTENT);
    UUID blobId = support.blobIdOf(uploadId);
    issueEvidenceService.publishHumanUpload(issueId, uploadId);
    return blobId;
  }

  private Session bootstrap(UUID issueId, String agentName) {
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    AcceptedCommands accepted =
        bootstrapService.bootstrapIssueAgentSession(
            new BootstrapIssueAgentSessionRequest(
                issueId, agentName, sessionId, threadId, idempotencyKey, "Please review"));
    assertFalse(accepted.replayed(), "first bootstrap must not be a replay");
    UUID agentSessionId =
        jdbc.queryForObject(
            "select id from project_issue_agent_session where issue_id = ? and agent_name = ?",
            UUID.class,
            issueId,
            agentName);
    return new Session(agentSessionId, sessionId, threadId, idempotencyKey);
  }

  private Fixture createIssue(String executor, String reviewer) {
    Project project = projectService.createProject("Evidence project", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Publish evidence", "Desc", executor, reviewer, IssueStatus.TODO);
    return new Fixture(project.getId(), issue.getId(), executor, reviewer);
  }

  private String createTestAgent() {
    long id = FIXTURE_COUNTER.incrementAndGet();
    String providerName = "prov-" + id;
    String modelName = "mod-" + id;
    String agentName = "agent-" + id;
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) "
            + "values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        UUID.randomUUID());
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config) values (?, ?, ?, ?::jsonb)",
        providerName,
        modelName,
        "wire-" + id,
        "{\"limit\":{\"context\":128000,\"output\":8192},"
            + "\"abilities\":{\"tools\":true,\"reasoning\":true,\"inputModalities\":[\"TEXT\"]},"
            + "\"variants\":[{\"id\":\"default\"}],\"defaultVariant\":\"default\","
            + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"standard\","
            + "\"serviceTier\":\"standard\",\"serviceTierMultiplier\":1.0,"
            + "\"version\":\"2026-01-01\",\"inputPerMillionTokens\":0,"
            + "\"outputPerMillionTokens\":0,\"cacheReadPerMillionTokens\":0,"
            + "\"cacheWritePerMillionTokens\":0,\"cacheWriteLongPerMillionTokens\":0,"
            + "\"reasoningPerMillionTokens\":0}}");
    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config) "
            + "values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);
    return agentName;
  }

  private record Fixture(UUID projectId, UUID issueId, String executor, String reviewer) {}

  private record Session(UUID agentSessionId, UUID sessionId, UUID threadId, UUID idempotencyKey) {}
}
