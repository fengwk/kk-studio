package fun.fengwk.kkstudio.web.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueRunSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.BootstrapProjectSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ProjectHarnessSessionBootstrapService} 针对真实 PostgreSQL Testcontainers 的端到端集成测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 PROJECT 与 ISSUE_RUN 会话在单一数据库事务内原子创建 Session、ROOT Entry、Initial Thread Command 与关系行；
 *   <li>验证幂等 exact replay 语义，不重复写入行，版本/序号不推进，且准确返回 replayed=true；
 *   <li>验证绑定冲突、无效所有者、终态运行等校验失败时的完全事务回滚（无悬挂 Harness 或关系孤儿行）；
 *   <li>验证 harness_session_owner_guard 单一所有权互斥约束，跨 OwnerType（如 PROJECT 与 ISSUE_RUN）互斥无孤儿。
 * </ul>
 */
class ProjectHarnessSessionBootstrapIntegrationTest extends WebPostgresTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();

  @Autowired private ProjectHarnessSessionBootstrapService bootstrapService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void bootstrapProjectSession_atomicallyCreatesSessionAndHarnessRows() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Project Alpha", "Description", agentName);
    UUID projectId = project.getId();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String initialMessage = "Coordinate initial project roadmap";

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            projectId, sessionId, threadId, idempotencyKey, initialMessage);
    AcceptedCommands result = bootstrapService.bootstrapProjectSession(request);

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals(sessionId, result.session().id());
    assertEquals(threadId, result.thread().id());
    assertEquals(1, result.acceptedCommands().size());

    UserMessageCommandPayload payload =
        (UserMessageCommandPayload) result.acceptedCommands().getFirst().payload();
    assertEquals(AgentMessageRole.USER, payload.message().role());
    assertEquals(
        initialMessage, ((TextMessageContent) payload.message().contents().getFirst()).text());

    // 验证数据库内所有行的原子持久化
    assertEquals(1, count("project_session", "project_id", projectId));
    assertEquals(1, count("project_session", "session_id", sessionId));
    assertEquals(1, count("harness_session_owner_guard", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapProjectSession_exactReplayIdempotencyNoVersionOrCountChange() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Replay Project", "Desc", agentName);
    UUID projectId = project.getId();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String initialMessage = "Coordinate roadmap once";

    BootstrapProjectSessionRequest request =
        new BootstrapProjectSessionRequest(
            projectId, sessionId, threadId, idempotencyKey, initialMessage);

    AcceptedCommands first = bootstrapService.bootstrapProjectSession(request);
    assertFalse(first.replayed());

    Long threadVersion1 =
        jdbcTemplate.queryForObject(
            "select version from harness_thread where id = ?", Long.class, threadId);
    Long nextCmdSeq1 =
        jdbcTemplate.queryForObject(
            "select next_command_sequence from harness_thread where id = ?", Long.class, threadId);

    // 第二次相同请求，触发 exact replay
    AcceptedCommands second = bootstrapService.bootstrapProjectSession(request);
    assertTrue(second.replayed());
    assertEquals(sessionId, second.session().id());
    assertEquals(threadId, second.thread().id());

    Long threadVersion2 =
        jdbcTemplate.queryForObject(
            "select version from harness_thread where id = ?", Long.class, threadId);
    Long nextCmdSeq2 =
        jdbcTemplate.queryForObject(
            "select next_command_sequence from harness_thread where id = ?", Long.class, threadId);

    assertEquals(threadVersion1, threadVersion2, "Thread version must not advance on exact replay");
    assertEquals(
        nextCmdSeq1, nextCmdSeq2, "Thread next_command_sequence must not advance on exact replay");

    // 表内行数不重复膨胀
    assertEquals(1, count("project_session", "project_id", projectId));
    assertEquals(1, count("harness_session_owner_guard", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapProjectSession_rejectsDifferentSessionWhenAlreadyBound() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Bound Project", "Desc", agentName);
    UUID projectId = project.getId();
    UUID session1 = UUID.randomUUID();
    UUID session2 = UUID.randomUUID();

    bootstrapService.bootstrapProjectSession(
        new BootstrapProjectSessionRequest(
            projectId, session1, UUID.randomUUID(), UUID.randomUUID(), "First session"));

    assertThrows(
        AiValidationException.class,
        () ->
            bootstrapService.bootstrapProjectSession(
                new BootstrapProjectSessionRequest(
                    projectId, session2, UUID.randomUUID(), UUID.randomUUID(), "Second session")));

    // session1 正常存在且不受影响
    assertEquals(1, count("project_session", "project_id", projectId));
    assertEquals(1, count("project_session", "session_id", session1));
    assertEquals(1, count("harness_session_owner_guard", "session_id", session1));
    assertEquals(1, count("harness_session", "id", session1));

    // session2 无任何数据写入
    assertEquals(0, count("project_session", "session_id", session2));
    assertEquals(0, count("harness_session", "id", session2));
    assertEquals(0, count("harness_session_owner_guard", "session_id", session2));
  }

  @Test
  void bootstrapProjectSession_rollbackOnArchivedProjectLeavesNoDanglingRows() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Archived Project", "Desc", agentName);
    projectService.archiveProject(project.getId(), project.getVersion());

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();

    assertThrows(
        AiValidationException.class,
        () ->
            bootstrapService.bootstrapProjectSession(
                new BootstrapProjectSessionRequest(
                    project.getId(),
                    sessionId,
                    threadId,
                    idempotencyKey,
                    "Should fail on archived project")));

    // 验证事务完全回滚，无残留孤儿行
    assertEquals(0, count("project_session", "project_id", project.getId()));
    assertEquals(0, count("project_session", "session_id", sessionId));
    assertEquals(0, count("harness_session_owner_guard", "session_id", sessionId));
    assertEquals(0, count("harness_session", "id", sessionId));
    assertEquals(0, count("harness_entry", "session_id", sessionId));
    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("harness_thread_command", "thread_id", threadId));
    assertEquals(0, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapIssueRunSession_atomicallyCreatesSessionAndHarnessRows() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Issue Run Project", "Desc", agentName);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Issue 1", "Desc", agentName, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String initialMessage = "Execute task 1";

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(
            runId, sessionId, threadId, idempotencyKey, initialMessage);
    AcceptedCommands result = bootstrapService.bootstrapIssueRunSession(request);

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals(sessionId, result.session().id());
    assertEquals(threadId, result.thread().id());
    assertEquals(1, result.acceptedCommands().size());

    // 验证原子持久化
    assertEquals(1, count("issue_run_session", "run_id", runId));
    assertEquals(1, count("issue_run_session", "session_id", sessionId));
    assertEquals(1, count("harness_session_owner_guard", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapIssueRunSession_exactReplayIdempotencyNoVersionOrCountChange() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Run Replay Project", "Desc", agentName);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Issue Replay", "Desc", agentName, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String initialMessage = "Execute task replay";

    BootstrapIssueRunSessionRequest request =
        new BootstrapIssueRunSessionRequest(
            runId, sessionId, threadId, idempotencyKey, initialMessage);

    AcceptedCommands first = bootstrapService.bootstrapIssueRunSession(request);
    assertFalse(first.replayed());

    Long threadVersion1 =
        jdbcTemplate.queryForObject(
            "select version from harness_thread where id = ?", Long.class, threadId);
    Long nextCmdSeq1 =
        jdbcTemplate.queryForObject(
            "select next_command_sequence from harness_thread where id = ?", Long.class, threadId);

    AcceptedCommands second = bootstrapService.bootstrapIssueRunSession(request);
    assertTrue(second.replayed());

    Long threadVersion2 =
        jdbcTemplate.queryForObject(
            "select version from harness_thread where id = ?", Long.class, threadId);
    Long nextCmdSeq2 =
        jdbcTemplate.queryForObject(
            "select next_command_sequence from harness_thread where id = ?", Long.class, threadId);

    assertEquals(threadVersion1, threadVersion2, "Thread version must not advance on exact replay");
    assertEquals(
        nextCmdSeq1, nextCmdSeq2, "Thread next_command_sequence must not advance on exact replay");

    assertEquals(1, count("issue_run_session", "run_id", runId));
    assertEquals(1, count("harness_session_owner_guard", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapIssueRunSession_rejectsDifferentSessionWhenAlreadyBound() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Run Bound Project", "Desc", agentName);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Issue Bound", "Desc", agentName, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    UUID session1 = UUID.randomUUID();
    UUID session2 = UUID.randomUUID();

    bootstrapService.bootstrapIssueRunSession(
        new BootstrapIssueRunSessionRequest(
            runId, session1, UUID.randomUUID(), UUID.randomUUID(), "First run session"));

    assertThrows(
        AiValidationException.class,
        () ->
            bootstrapService.bootstrapIssueRunSession(
                new BootstrapIssueRunSessionRequest(
                    runId, session2, UUID.randomUUID(), UUID.randomUUID(), "Second run session")));

    // session1 正常保留
    assertEquals(1, count("issue_run_session", "run_id", runId));
    assertEquals(1, count("issue_run_session", "session_id", session1));
    assertEquals(1, count("harness_session_owner_guard", "session_id", session1));

    // session2 无任何数据写入
    assertEquals(0, count("issue_run_session", "session_id", session2));
    assertEquals(0, count("harness_session", "id", session2));
    assertEquals(0, count("harness_session_owner_guard", "session_id", session2));
  }

  @Test
  void bootstrapIssueRunSession_rollbackOnTerminalRunValidationLeavesNoDanglingRows() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Terminal Project", "Desc", agentName);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Terminal Issue", "Desc", agentName, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(3600), 10);
    UUID runId = run.getId();

    // 将 Run 置为 FAILED 终态
    issueRunService.failRun(runId, IssueRunStatus.FAILED, "Execution failed");

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();

    assertThrows(
        AiValidationException.class,
        () ->
            bootstrapService.bootstrapIssueRunSession(
                new BootstrapIssueRunSessionRequest(
                    runId, sessionId, threadId, idempotencyKey, "Should fail on terminal run")));

    // 验证事务回滚，无残留 Harness 行或关系行
    assertEquals(0, count("issue_run_session", "run_id", runId));
    assertEquals(0, count("issue_run_session", "session_id", sessionId));
    assertEquals(0, count("harness_session_owner_guard", "session_id", sessionId));
    assertEquals(0, count("harness_session", "id", sessionId));
    assertEquals(0, count("harness_entry", "session_id", sessionId));
    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("harness_thread_command", "thread_id", threadId));
    assertEquals(0, count("harness_work", "target_id", threadId));
  }

  @Test
  void singleOwnerGuardEnforcementAcrossOwnerTypesNoOrphan() {
    String agentName = createTestAgent();
    Project proj = projectService.createProject("Project Owner", "Desc", agentName);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Issue Cross Guard", "Desc", agentName, null, IssueStatus.TODO);
    IssueRun run =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(3600), 10);

    UUID sharedSessionId = UUID.randomUUID();

    // Project 先成功占用 sharedSessionId (OwnerType = PROJECT)
    bootstrapService.bootstrapProjectSession(
        new BootstrapProjectSessionRequest(
            proj.getId(),
            sharedSessionId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Project initial prompt"));

    assertEquals(1, count("project_session", "project_id", proj.getId()));
    assertEquals(1, count("project_session", "session_id", sharedSessionId));
    assertEquals(1, count("harness_session_owner_guard", "session_id", sharedSessionId));

    // 跨 OwnerType: IssueRun 尝试占用同一个 sharedSessionId，被互斥锁/guard 拦截
    assertThrows(
        Exception.class,
        () ->
            bootstrapService.bootstrapIssueRunSession(
                new BootstrapIssueRunSessionRequest(
                    run.getId(),
                    sharedSessionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Conflicting run prompt")));

    // 验证 IssueRun 关联行绝未创建（无孤儿关系）
    assertEquals(0, count("issue_run_session", "run_id", run.getId()));
    assertEquals(0, count("issue_run_session", "session_id", sharedSessionId));

    // sharedSessionId 依然唯一且安全地归属于 Project
    assertEquals(1, count("harness_session_owner_guard", "session_id", sharedSessionId));
    assertEquals(1, count("project_session", "project_id", proj.getId()));
    assertEquals(1, count("project_session", "session_id", sharedSessionId));
  }

  private int count(String table, String column, Object value) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    return count != null ? count : 0;
  }

  private String createTestAgent() {
    long id = FIXTURE_COUNTER.incrementAndGet();
    String providerName = "prov-" + id;
    String modelName = "mod-" + id;
    String agentName = "agent-" + id;

    jdbcTemplate.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) "
            + "values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        UUID.randomUUID());

    String modelConfig =
        "{\"limit\":{\"context\":128000,\"output\":8192},"
            + "\"abilities\":{\"tools\":true,\"reasoning\":true,\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
            + "\"defaultVariant\":\"quality\","
            + "\"variants\":[{\"id\":\"quality\",\"reasoningEffort\":\"high\"},"
            + "{\"id\":\"fast\",\"reasoningEffort\":\"off\"}],"
            + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
            + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
            + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
            + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
            + "\"cacheWritePerMillionTokens\":0.4,"
            + "\"cacheWriteLongPerMillionTokens\":0.5,"
            + "\"reasoningPerMillionTokens\":3.6}}";

    jdbcTemplate.update(
        "insert into agent_model (provider_name, name, model_id, config) "
            + "values (?, ?, ?, ?::jsonb)",
        providerName,
        modelName,
        "wire-" + id,
        modelConfig);

    jdbcTemplate.update(
        "insert into agent_definition (name, model_provider_name, model_name, config) "
            + "values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);

    return agentName;
  }
}
