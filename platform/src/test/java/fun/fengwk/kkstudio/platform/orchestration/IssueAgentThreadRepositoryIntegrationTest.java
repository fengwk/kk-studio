package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@code project_issue_agent_thread} 稳定绑定在真实 PostgreSQL 上的契约：插入/查找/删除、FK 防悬空、Thread 全局唯一，以及存在 Run
 * 时删除被 RESTRICT 拒绝（深删除不允许绕过业务清理硬删已绑定 Thread/Session）。
 */
class IssueAgentThreadRepositoryIntegrationTest extends OwnerTestSupport {

  @Autowired private IssueAgentThreadRepository issueAgentThreadRepository;

  /** 插入后可按 (issue, agent) 读取，删除返回受影响行数且重复删除幂等。 */
  @Test
  void insertFindDeleteRoundTrip() {
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    String agentName = agentDefinition();
    UUID sessionId = uuid();
    sessionRow(sessionId);
    UUID threadId = threadRow(sessionId);

    assertTrue(
        issueAgentThreadRepository.insert(new IssueAgentThread(issueId, agentName, threadId)));
    assertEquals(
        new IssueAgentThread(issueId, agentName, threadId),
        issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, agentName));
    assertNull(issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, "other-agent"));

    assertEquals(1, issueAgentThreadRepository.deleteByIssueIdAndAgentName(issueId, agentName));
    assertEquals(0, issueAgentThreadRepository.deleteByIssueIdAndAgentName(issueId, agentName));
    assertNull(issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, agentName));
  }

  /** FK：Issue、AgentDefinition 与 harness_thread 都必须存在，绑定不能凭空悬空。 */
  @Test
  void foreignKeysRejectOrphanedRows() {
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    String agentName = agentDefinition();
    UUID sessionId = uuid();
    sessionRow(sessionId);
    UUID threadId = threadRow(sessionId);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> issueAgentThreadRepository.insert(new IssueAgentThread(uuid(), agentName, threadId)),
        "missing issue must fail the issue_id FK");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            issueAgentThreadRepository.insert(
                new IssueAgentThread(issueId, "missing-agent", threadId)),
        "missing agent definition must fail the agent_name FK");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> issueAgentThreadRepository.insert(new IssueAgentThread(issueId, agentName, uuid())),
        "missing harness thread must fail the thread_id FK");
  }

  /** 稳定身份不可重绑：同一 (issue, agent) 只有一条绑定，同一 Thread 也只能被一个 Agent/Issue 持有。 */
  @Test
  void stableIdentityAndThreadAreNeverReboundOrShared() {
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    String firstAgent = agentDefinition();
    String secondAgent = agentDefinition();
    UUID firstSession = uuid();
    UUID secondSession = uuid();
    sessionRow(firstSession);
    sessionRow(secondSession);
    UUID firstThread = threadRow(firstSession);
    UUID secondThread = threadRow(secondSession);

    assertTrue(
        issueAgentThreadRepository.insert(new IssueAgentThread(issueId, firstAgent, firstThread)));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            issueAgentThreadRepository.insert(
                new IssueAgentThread(issueId, firstAgent, secondThread)),
        "same (issue, agent) must not rebind to another thread");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            issueAgentThreadRepository.insert(
                new IssueAgentThread(issueId, secondAgent, firstThread)),
        "same thread must not be shared by another agent");
    assertEquals(
        new IssueAgentThread(issueId, firstAgent, firstThread),
        issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, firstAgent));
  }

  /** 并发绑定同一 Thread 时必须恰好一个成功：数据库唯一键是最终的 owner 互斥边界，不能出现双 owner。 */
  @Test
  void concurrentBindingsOfSameThreadKeepExactlyOneOwner() throws Exception {
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    String firstAgent = agentDefinition();
    String secondAgent = agentDefinition();
    UUID sessionId = uuid();
    sessionRow(sessionId);
    UUID threadId = threadRow(sessionId);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Boolean>> races =
          List.of(
              () -> tryBind(issueId, firstAgent, threadId),
              () -> tryBind(issueId, secondAgent, threadId));
      List<Future<Boolean>> results = executor.invokeAll(races);
      long winners = 0;
      for (Future<Boolean> result : results) {
        if (result.get()) {
          winners++;
        }
      }
      assertEquals(1, winners, "exactly one concurrent owner may win the thread");
      assertEquals(
          1L,
          count("select count(*) from project_issue_agent_thread where thread_id = ?", threadId));
    } finally {
      executor.shutdownNow();
    }
  }

  /** Run 引用绑定与 Thread 时删除必须被 RESTRICT 拒绝，且不能留下半删状态：Issue 深删除必须先清理 Run，绝不允许绕过业务清理硬删历史。 */
  @Test
  void runReferenceBlocksBindingAndThreadDeletion() {
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    String agentName = agentDefinition();
    UUID sessionId = uuid();
    sessionRow(sessionId);
    UUID threadId = threadRow(sessionId);
    UUID rootEntryId =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?", UUID.class, threadId);
    bindIssueAgentThread(issueId, agentName, threadId);
    jdbc.update(
        "insert into project_issue_stage_budget (issue_id, state, max_runs, budget_after_ordinal)"
            + " values (?, 'DESIGN', 3, 0)",
        issueId);
    jdbc.update(
        "insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id,"
            + " status, start_entry_id, remaining_execution_ms, active_since)"
            + " values (?, ?, 1, 'DESIGN', ?, ?, 'RUNNING', ?, 60000, current_timestamp)",
        uuid(),
        issueId,
        sessionId,
        threadId,
        rootEntryId);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> issueAgentThreadRepository.deleteByIssueIdAndAgentName(issueId, agentName),
        "bound history must not be deleted while a Run references it");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("delete from harness_thread where id = ?", threadId),
        "harness thread must not be deleted while a Run references it");
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ? and agent_name = ?",
            issueId,
            agentName));
    assertEquals(1L, count("select count(*) from harness_thread where id = ?", threadId));
    assertEquals(1L, count("select count(*) from harness_session where id = ?", sessionId));

    // 业务清理顺序：先终结并删除 Run，绑定与 Thread 才允许被删除。
    jdbc.update("delete from project_issue_run where issue_id = ?", issueId);
    assertEquals(1, issueAgentThreadRepository.deleteByIssueIdAndAgentName(issueId, agentName));
    assertEquals(1, jdbc.update("delete from harness_thread where id = ?", threadId));
  }

  private boolean tryBind(UUID issueId, String agentName, UUID threadId) {
    try {
      return issueAgentThreadRepository.insert(new IssueAgentThread(issueId, agentName, threadId));
    } catch (DataIntegrityViolationException expected) {
      return false;
    }
  }
}
