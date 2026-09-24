package fun.fengwk.kkstudio.platform.project.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.project.ProjectTestSupport;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.UUID;

/**
 * IssueAgentSession 引导循环依赖的持久化契约集成测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>生产引导顺序不可避免：归属行 {@code project_issue_agent_session} 必须先于 Harness Session/Thread 建立（owner
 *       授权要求归属先存在），三者在同一物理事务内提交。因此该表指向 Harness 的两个外键必须延迟到提交时校验：同一事务内先插归属、 后插 Session/Thread
 *       必须可以提交，且提交后归属行与真实存在的 Session/Thread 严格对应；
 *   <li>同一事务回滚必须不留任何孤儿（归属行、Session、Thread 一起消失）；
 *   <li>完整性不因延迟而削弱：删除侧仍为立即生效的 {@code ON DELETE RESTRICT}，存在归属行时不得删除 Harness Session。
 * </ul>
 *
 * <p>本测试不使用 HarnessRuntime：platform 独立测试上下文不装配运行时，因此这里直接以真实仓储、真实 Harness 表与 真实 Spring
 * 事务边界验证持久化契约，而非走完整的接受链。
 */
class ProjectHarnessSessionBootstrapIntegrationTest extends ProjectTestSupport {

  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;
  @Autowired private IssueAgentSessionRepository issueAgentSessionRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void ownershipCycleCommitsInsideOnePhysicalTransaction() {
    Fixtures fixtures = fixtures("Cycle project", "Cycle issue");
    UUID bindingId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    IssueAgentSession binding = binding(fixtures, bindingId, sessionId, threadId);

    newTransaction()
        .executeWithoutResult(
            status -> {
              // 归属行先建立：此刻 Harness Session/Thread 尚不存在，只有延迟外键才允许该顺序
              assertEquals(bindingId, issueAgentSessionRepository.bindOrGet(binding).getId());
              insertHarnessSessionAndThread(sessionId, threadId);
              jdbcTemplate.update(
                  "insert into session_owner (session_id, issue_agent_session_id, created_at)"
                      + " values (?, ?, current_timestamp)",
                  sessionId,
                  bindingId);
            });

    // 提交后延迟外键已被校验：归属行必须能 join 到真实存在的 Session 与工作 Branch
    assertEquals(
        1,
        count(
            "select count(*) from project_issue_agent_session s"
                + " join harness_session hs on hs.id = s.session_id"
                + " join harness_thread ht on ht.id = s.thread_id"
                + " where s.id = ? and s.session_id = ? and s.thread_id = ?",
            bindingId,
            sessionId,
            threadId));
    assertEquals(
        1,
        count(
            "select count(*) from session_owner where session_id = ? and issue_agent_session_id = ?",
            sessionId,
            bindingId));
    assertEquals(bindingId, issueAgentSessionRepository.findByThreadId(threadId).getId());

    // 删除侧仍是立即 RESTRICT：归属边存在时不得删除 Harness Session
    assertThrows(
        DataAccessException.class,
        () -> jdbcTemplate.update("delete from harness_session where id = ?", sessionId));
  }

  @Test
  void rolledBackTransactionLeavesNoOwnershipOrHarnessRows() {
    Fixtures fixtures = fixtures("Rollback project", "Rollback issue");
    UUID bindingId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    IssueAgentSession binding = binding(fixtures, bindingId, sessionId, threadId);

    newTransaction()
        .executeWithoutResult(
            status -> {
              issueAgentSessionRepository.bindOrGet(binding);
              insertHarnessSessionAndThread(sessionId, threadId);
              status.setRollbackOnly();
            });

    assertEquals(
        0, count("select count(*) from project_issue_agent_session where id = ?", bindingId));
    assertEquals(0, count("select count(*) from harness_session where id = ?", sessionId));
    assertEquals(0, count("select count(*) from harness_thread where id = ?", threadId));
    assertEquals(0, count("select count(*) from harness_entry where session_id = ?", sessionId));
    assertEquals(0, count("select count(*) from session_owner where session_id = ?", sessionId));
  }

  private Fixtures fixtures(String projectTitle, String issueTitle) {
    String agent = createTestAgent();
    Project project = projectService.createProject(projectTitle, "Description", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), issueTitle, "Description", agent, null, IssueStatus.TODO);
    return new Fixtures(issue.getId(), agent);
  }

  private static IssueAgentSession binding(
      Fixtures fixtures, UUID bindingId, UUID sessionId, UUID threadId) {
    return IssueAgentSession.builder()
        .id(bindingId)
        .issueId(fixtures.issueId())
        .agentName(fixtures.agentName())
        .sessionId(sessionId)
        .threadId(threadId)
        .build();
  }

  /** 以与 Harness 接受相同的必要行形状插入 Session、ROOT Entry 与工作 Branch。 */
  private void insertHarnessSessionAndThread(UUID sessionId, UUID threadId) {
    jdbcTemplate.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "bootstrap-" + sessionId);
    UUID rootEntryId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, current_timestamp)",
        rootEntryId,
        sessionId);
    jdbcTemplate.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " yolo_enabled, next_command_sequence, version, created_at, updated_at) values (?, ?,"
            + " ?, ?, ?, true, 1, 0, current_timestamp, current_timestamp)",
        threadId,
        sessionId,
        rootEntryId,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "branch-" + threadId);
  }

  private TransactionTemplate newTransaction() {
    return new TransactionTemplate(transactionManager);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
    return value != null ? value : 0;
  }

  private record Fixtures(UUID issueId, String agentName) {}
}
