package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issue 公开证据与项目/Issue 生命周期在 web 组合根的集成测试（真实 PostgreSQL + 真实引导/深删除 + 内存 S3 假件）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li><b>Session 删除不回收 Issue 证据</b>：深删除 Session 只释放该 Session 自己的引用，Issue 证据行与 Issue 持有引用保留，Blob
 *       仍可用；
 *   <li><b>Issue 删除释放全部证据引用</b>：深删除 Issue 释放其持有的证据 Blob 引用，相关表行按依赖顺序清理；
 *   <li><b>Project 删除释放全部引用</b>：证据行消失、Issue 与 Session 引用都释放、Blob 归零切 DELETING，且删除顺序满足外键约束；
 *   <li><b>活跃或 UNKNOWN 门禁拒绝删除</b>：存在活动 Run 或未核查 UNKNOWN 时严格拒绝删除 Project / Issue。
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
  @Autowired private SessionDeletionOrchestrator sessionDeletionOrchestrator;
  @Autowired private SessionBlobRefManager refManager;
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
  void deletingSessionReleasesOnlyItsOwnReferenceAndKeepsIssueEvidence() {
    String executor = createTestAgent();
    Fixture fixture = createIssue(executor);
    IssueRun run = issueRunService.acceptRun(fixture.issueId(), "req:accept:" + UUID.randomUUID());
    UUID blobId = publishHumanEvidence(fixture.issueId(), "keep.txt");

    // 让 Session 侧也引用该 blob
    tx.executeWithoutResult(status -> refManager.retainRef(run.getSessionId(), blobId));
    assertEquals(2L, support.refCount(blobId), "issue + executor session hold one reference each");

    // 收尾并删除 Run，以便能独立测试 Session 删除
    UUID endEntryId = appendHistoryEntry(run.getThreadId(), run.getSessionId());
    issueRunService.completeRun(
        run.getId(), run.getVersion(), "req:comp:" + UUID.randomUUID(), endEntryId, null, null);
    jdbc.update("delete from project_issue_run where id = ?", run.getId());

    sessionDeletionOrchestrator.deleteSessionsByOwner(
        new OwnerRef.IssueAgent(fixture.issueId(), executor));

    // Session 侧引用随 Session 消失，Issue 证据与 Issue 持有的引用保留，Blob 仍可用
    assertEquals(0, support.count("session_blob_ref", "session_id", run.getSessionId()));
    assertEquals(0, support.count("project_issue_agent_thread", "issue_id", fixture.issueId()));
    assertEquals(1, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertEquals(1L, support.refCount(blobId));
    assertEquals("ACTIVE", support.blobState(blobId));
  }

  @Test
  void deletingIssueReleasesEvidenceBlobRefsAndCleansRows() {
    String executor = createTestAgent();
    Fixture fixture = createIssue(executor);
    UUID blobId = publishHumanEvidence(fixture.issueId(), "issue-evidence.txt");
    assertEquals(1L, support.refCount(blobId));

    Issue issue = issueService.getIssue(fixture.issueId());
    issueService.deleteIssue(issue.getId(), issue.getVersion());

    assertEquals(0, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertEquals(0, support.count("project_issue", "id", fixture.issueId()));
    assertEquals(0L, support.refCount(blobId));
    assertEquals("DELETING", support.blobState(blobId));
  }

  @Test
  void deletingProjectReleasesIssueAndSessionReferencesInForeignKeyOrder() {
    String executor = createTestAgent();
    Fixture fixture = createIssue(executor);
    IssueRun run = issueRunService.acceptRun(fixture.issueId(), "req:accept:" + UUID.randomUUID());
    UUID blobId = publishHumanEvidence(fixture.issueId(), "project.txt");

    tx.executeWithoutResult(status -> refManager.retainRef(run.getSessionId(), blobId));
    assertEquals(2L, support.refCount(blobId));

    UUID endEntryId = appendHistoryEntry(run.getThreadId(), run.getSessionId());
    issueRunService.completeRun(
        run.getId(), run.getVersion(), "req:comp:" + UUID.randomUUID(), endEntryId, null, null);

    projectService.deleteProject(
        fixture.projectId(), projectService.getProject(fixture.projectId()).getVersion());

    // 证据行消失、Issue 与 Session 引用全部释放、Blob 归零切 DELETING：整个删除过程无外键冲突
    assertEquals(0, support.count("project_issue_evidence", "issue_id", fixture.issueId()));
    assertEquals(0, support.count("session_blob_ref", "blob_id", blobId));
    assertEquals(0, support.count("project_issue", "id", fixture.issueId()));
    assertEquals(0, support.count("project", "id", fixture.projectId()));
    assertEquals(0L, support.refCount(blobId));
    assertEquals("DELETING", support.blobState(blobId));
  }

  @Test
  void deleteProjectRejectsWhenIssueHasActiveRunOrUnknownGate() {
    String executor = createTestAgent();
    Fixture fixture = createIssue(executor);
    IssueRun run = issueRunService.acceptRun(fixture.issueId(), "req:accept:" + UUID.randomUUID());

    // 活动 Run 拒绝删除
    assertThrows(
        ProjectValidationException.class,
        () ->
            projectService.deleteProject(
                fixture.projectId(), projectService.getProject(fixture.projectId()).getVersion()));

    // 置为 UNKNOWN 门禁
    UUID endEntryId = appendHistoryEntry(run.getThreadId(), run.getSessionId());
    issueRunService.markUnknown(
        run.getId(),
        run.getVersion(),
        "req:unk:" + UUID.randomUUID(),
        endEntryId,
        "Unknown failure");

    // UNKNOWN 门禁拒绝删除
    assertThrows(
        ProjectValidationException.class,
        () ->
            projectService.deleteProject(
                fixture.projectId(), projectService.getProject(fixture.projectId()).getVersion()));

    // 人工核查后解除 UNKNOWN
    Issue unkIssue = issueService.getIssue(fixture.issueId());
    issueService.resolveUnknown(
        unkIssue.getId(),
        unkIssue.getVersion(),
        "req:res:" + UUID.randomUUID(),
        "Manually verified");

    // 现在可以成功删除
    projectService.deleteProject(
        fixture.projectId(), projectService.getProject(fixture.projectId()).getVersion());
    assertEquals(0, support.count("project", "id", fixture.projectId()));
  }

  private UUID publishHumanEvidence(UUID issueId, String filename) {
    UUID uploadId = support.readyUpload(filename, EVIDENCE_CONTENT);
    UUID blobId = support.blobIdOf(uploadId);
    issueEvidenceService.publishHumanUpload(issueId, uploadId);
    return blobId;
  }

  private UUID appendHistoryEntry(UUID threadId, UUID sessionId) {
    UUID parentEntryId =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?", UUID.class, threadId);
    UUID entryId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, now())",
        entryId,
        sessionId,
        parentEntryId);
    jdbc.update(
        "update harness_thread set head_entry_id = ?, updated_at = now() where id = ?",
        entryId,
        threadId);
    return entryId;
  }

  private Fixture createIssue(String executor) {
    String workflowJson =
        "{\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
            + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\""
            + executor
            + "\",\"instructions\":\"完成方案\",\"maxRuns\":3,\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}";
    Project project = projectService.createProject("Evidence Project", "Desc", false);
    projectService.updateWorkflow(project.getId(), project.getVersion(), workflowJson);
    Issue issue = issueService.createIssue(project.getId(), "Evidence Task", "Desc");
    issueService.transition(
        issue.getId(), issue.getVersion(), "req:trans:" + UUID.randomUUID(), "DESIGN");
    return new Fixture(project.getId(), issue.getId(), executor);
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

  private record Fixture(UUID projectId, UUID issueId, String executor) {}
}
