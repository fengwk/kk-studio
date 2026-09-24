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
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.controller.IssueReconcileOutcome;
import fun.fengwk.kkstudio.platform.project.controller.IssueReconciler;
import fun.fengwk.kkstudio.platform.project.model.ClaimedIssueWork;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.platform.project.session.BootstrapIssueAgentSessionRequest;
import fun.fengwk.kkstudio.platform.project.session.ProjectHarnessSessionBootstrapService;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
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
 *   <li>验证 ISSUE_AGENT_SESSION 会话在单一数据库事务内原子创建 Session、ROOT Entry、Initial Thread Command 与关系行；
 *   <li>验证幂等 exact replay 语义，不重复写入行，版本/序号不推进，且准确返回 replayed=true；
 *   <li>验证绑定冲突、无效所有者、已归档项目等校验失败时的完全事务回滚（无悬挂 Harness 或关系孤儿行）；
 *   <li>验证 session_owner 主键单一所有权互斥约束，跨 OwnerType（如 CHAT 与 ISSUE_AGENT_SESSION）互斥无孤儿。
 * </ul>
 */
class ProjectHarnessSessionBootstrapIntegrationTest extends WebPostgresTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();

  @Autowired private ProjectHarnessSessionBootstrapService bootstrapService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueWorkStore workStore;
  @Autowired private IssueReconciler issueReconciler;
  @Autowired private ChatService chatService;
  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void bootstrapIssueAgentSession_atomicallyCreatesSessionAndHarnessRows() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Project Alpha", "Description", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Issue Alpha", "Desc", agentName, null, IssueStatus.TODO);
    UUID issueId = issue.getId();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String initialMessage = "Execute initial issue task";

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            issueId, agentName, sessionId, threadId, idempotencyKey, initialMessage);
    AcceptedCommands result = bootstrapService.bootstrapIssueAgentSession(request);

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
    UUID agentSessionId =
        jdbcTemplate.queryForObject(
            "select id from project_issue_agent_session where issue_id = ? and agent_name = ?",
            UUID.class,
            issueId,
            agentName);
    assertEquals(1, count("session_owner", "issue_agent_session_id", agentSessionId));
    assertEquals(1, count("session_owner", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapIssueAgentSession_exactReplayIdempotencyNoVersionOrCountChange() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Replay Project", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Issue Replay", "Desc", agentName, null, IssueStatus.TODO);
    UUID issueId = issue.getId();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String initialMessage = "Execute task replay";

    BootstrapIssueAgentSessionRequest request =
        new BootstrapIssueAgentSessionRequest(
            issueId, agentName, sessionId, threadId, idempotencyKey, initialMessage);

    AcceptedCommands first = bootstrapService.bootstrapIssueAgentSession(request);
    assertFalse(first.replayed());

    Long threadVersion1 =
        jdbcTemplate.queryForObject(
            "select version from harness_thread where id = ?", Long.class, threadId);
    Long nextCmdSeq1 =
        jdbcTemplate.queryForObject(
            "select next_command_sequence from harness_thread where id = ?", Long.class, threadId);

    // 第二次相同请求，触发 exact replay
    AcceptedCommands second = bootstrapService.bootstrapIssueAgentSession(request);
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

    UUID agentSessionId =
        jdbcTemplate.queryForObject(
            "select id from project_issue_agent_session where issue_id = ? and agent_name = ?",
            UUID.class,
            issueId,
            agentName);
    assertEquals(1, count("session_owner", "issue_agent_session_id", agentSessionId));
    assertEquals(1, count("session_owner", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
  }

  @Test
  void bootstrapIssueAgentSession_rejectsDifferentSessionWhenAlreadyBound() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Bound Project", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Issue Bound", "Desc", agentName, null, IssueStatus.TODO);
    UUID issueId = issue.getId();
    UUID session1 = UUID.randomUUID();
    UUID session2 = UUID.randomUUID();

    bootstrapService.bootstrapIssueAgentSession(
        new BootstrapIssueAgentSessionRequest(
            issueId, agentName, session1, UUID.randomUUID(), UUID.randomUUID(), "First session"));

    assertThrows(
        AiValidationException.class,
        () ->
            bootstrapService.bootstrapIssueAgentSession(
                new BootstrapIssueAgentSessionRequest(
                    issueId,
                    agentName,
                    session2,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Second session")));

    UUID agentSessionId =
        jdbcTemplate.queryForObject(
            "select id from project_issue_agent_session where issue_id = ? and agent_name = ?",
            UUID.class,
            issueId,
            agentName);
    assertEquals(1, count("session_owner", "issue_agent_session_id", agentSessionId));
    assertEquals(1, count("session_owner", "session_id", session1));
    assertEquals(1, count("harness_session", "id", session1));

    // session2 无任何数据写入
    assertEquals(0, count("session_owner", "session_id", session2));
    assertEquals(0, count("harness_session", "id", session2));
  }

  @Test
  void bootstrapIssueAgentSession_rollbackOnArchivedProjectLeavesNoDanglingRows() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Archived Project", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Archived Issue", "Desc", agentName, null, IssueStatus.TODO);
    projectService.archiveProject(project.getId(), project.getVersion());

    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();

    assertThrows(
        AiValidationException.class,
        () ->
            bootstrapService.bootstrapIssueAgentSession(
                new BootstrapIssueAgentSessionRequest(
                    issue.getId(),
                    agentName,
                    sessionId,
                    threadId,
                    idempotencyKey,
                    "Should fail on archived project")));

    // 验证事务完全回滚，无残留孤儿行
    assertEquals(0, count("project_issue_agent_session", "issue_id", issue.getId()));
    assertEquals(0, count("session_owner", "session_id", sessionId));
    assertEquals(0, count("harness_session", "id", sessionId));
    assertEquals(0, count("harness_entry", "session_id", sessionId));
    assertEquals(0, count("harness_thread", "id", threadId));
    assertEquals(0, count("harness_thread_command", "thread_id", threadId));
    assertEquals(0, count("harness_work", "target_id", threadId));
  }

  @Test
  void singleOwnerEnforcementAcrossOwnerTypesLeavesNoOrphan() {
    String agentName = createTestAgent();
    Project proj = projectService.createProject("Project Owner", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            proj.getId(), "Issue Cross Guard", "Desc", agentName, null, IssueStatus.TODO);

    UUID sharedSessionId = UUID.randomUUID();

    // 先创建 Chat 并绑定 sharedSessionId (OwnerType = CHAT)；Harness Session 行是归属边的持久前提
    ChatCreateDTO createReq = new ChatCreateDTO();
    createReq.setTitle("Chat Title");
    createReq.setAgentName(agentName);
    ChatDTO chat = chatService.createChat(createReq);
    UUID chatId = UUID.fromString(chat.getId());
    insertHarnessSession(sharedSessionId);
    chatSessionRepository.insert(sharedSessionId, chatId);

    assertEquals(1, count("session_owner", "chat_id", chatId));
    assertEquals(1, count("session_owner", "session_id", sharedSessionId));

    // 跨 OwnerType: IssueAgentSession 尝试占用同一个 sharedSessionId，被 session 主键拦截
    assertThrows(
        Exception.class,
        () ->
            bootstrapService.bootstrapIssueAgentSession(
                new BootstrapIssueAgentSessionRequest(
                    issue.getId(),
                    agentName,
                    sharedSessionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Conflicting issue prompt")));

    // 验证 IssueAgentSession 关联行绝未创建（无孤儿关系）
    assertEquals(0, count("project_issue_agent_session", "session_id", sharedSessionId));

    // sharedSessionId 依然唯一且安全地归属于 Chat
    assertEquals(1, count("session_owner", "session_id", sharedSessionId));
    assertEquals(1, count("session_owner", "chat_id", chatId));
  }

  @Test
  void reconcilerAtomicallyCreatesRunAndHarnessSession() {
    // 测试意图：P2 的真实 reconcile 事务必须一起持久化 Issue 状态、Run、Session、ROOT、Thread、Command 与归属边。
    String agentName = createTestAgent();
    Project project = projectService.createProject("Reconcile Project", "Desc", false, 0);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Reconcile Issue", "Desc", agentName, null, IssueStatus.TODO);
    Instant claimAt = Instant.now().plusSeconds(1);
    ClaimedIssueWork claim =
        workStore.claimNext(claimAt, "reconcile-worker", claimAt.plusSeconds(30)).orElseThrow();

    assertEquals(IssueReconcileOutcome.EXECUTOR_STARTED, issueReconciler.reconcile(claim));

    UUID issueId = issue.getId();
    UUID runId =
        jdbcTemplate.queryForObject(
            "select id from project_issue_run where issue_id = ?", UUID.class, issueId);
    UUID agentSessionId =
        jdbcTemplate.queryForObject(
            "select id from project_issue_agent_session where issue_id = ? and agent_name = ?",
            UUID.class,
            issueId,
            agentName);
    UUID sessionId =
        jdbcTemplate.queryForObject(
            "select session_id from session_owner where issue_agent_session_id = ?",
            UUID.class,
            agentSessionId);
    UUID threadId =
        jdbcTemplate.queryForObject(
            "select id from harness_thread where session_id = ?", UUID.class, sessionId);
    assertEquals(
        "IN_PROGRESS",
        jdbcTemplate.queryForObject(
            "select status from project_issue where id = ?", String.class, issueId));
    assertEquals(1, count("project_issue_run", "id", runId));
    assertEquals(1, count("project_issue_agent_session", "id", agentSessionId));
    assertEquals(1, count("session_owner", "issue_agent_session_id", agentSessionId));
    assertEquals(1, count("session_owner", "session_id", sessionId));
    assertEquals(1, count("harness_session", "id", sessionId));
    assertEquals(1, count("harness_entry", "session_id", sessionId));
    assertEquals(1, count("harness_thread", "id", threadId));
    assertEquals(1, count("harness_thread_command", "thread_id", threadId));
    assertEquals(1, count("harness_work", "target_id", threadId));
    assertNotNull(workStore.getWork(issueId));
  }

  private int count(String table, String column, Object value) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    return count != null ? count : 0;
  }

  /** 插入最小合法的 Harness Session 行：session_owner 归属边的持久前提。 */
  private void insertHarnessSession(UUID sessionId) {
    jdbcTemplate.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "session-" + sessionId);
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
