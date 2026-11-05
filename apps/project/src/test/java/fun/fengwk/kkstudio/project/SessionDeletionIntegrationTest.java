package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.platform.harness.builtin.store.PostgresqlBuiltinHarnessStore;
import fun.fengwk.kkstudio.platform.harness.store.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.platform.orchestration.OwnerTestSupport;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionFileRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.service.ProjectSessionOwnership;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Issue+Agent owner 深删除在真实 PostgreSQL 与外键约束下的契约：归属行与绑定先于 harness Thread/Session 删除，Issue/Project
 * 事实保持完整，外键冲突时整体回滚。
 *
 * <p>测试意图（断言真实 store 中的行数，不依赖 mock 调用顺序）：
 *
 * <ul>
 *   <li>Issue+Agent 归属：Session 由稳定绑定 Thread 解析，归属行（FK RESTRICT）先于 harness Thread/Session 删除，Issue
 *       自身不受影响；
 *   <li>Run 引用回滚：存在 Run 引用阻止绑定删除时，产品归属和 runtime 事实在同一事务中保留并回滚。
 * </ul>
 *
 * <p>本测试用真实 {@link PostgresqlHarnessStore} 与真实 PostgreSQL 外键路径驱动编排器：不覆盖任何全局 store bean（platform
 * 测试上下文已在扫描期注册占位 store），而是就地构造一个只在本测试内使用的 store 实例，并由测试提供外层事务——与生产调用方 （{@code @Transactional}
 * 入口）的语义完全一致，blob ref 的 MANDATORY 事务要求因此成立。
 */
class SessionDeletionIntegrationTest extends OwnerTestSupport {

  private static final Instant T0 = Instant.ofEpochMilli(1000);
  private static final Instant T1 = Instant.ofEpochMilli(2000);
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", BuiltinExecution.of(new ModelSelection("model", "v1")), null);

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private IssueAgentThreadRepository issueAgentThreadRepository;
  @Autowired private List<SessionOwnership> ownerships;
  @Autowired private SessionBlobRefManager refManager;
  @Autowired private SessionFileRepository sessionFileRepository;

  private HarnessStore store;
  private SessionDeletionOrchestrator orchestrator;
  private TransactionTemplate transactions;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUpOrchestrator() {
    store = new PostgresqlBuiltinHarnessStore(dataSource, transactionManager, UUID::randomUUID);
    ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);
    when(runtimes.getIfAvailable())
        .thenReturn(
            new HarnessRuntime(store, new DriverRegistry(), () -> CompactionConfig.DEFAULT));
    orchestrator =
        new SessionDeletionOrchestrator(ownerships, runtimes, refManager, sessionFileRepository);
    transactions = new TransactionTemplate(transactionManager);
  }

  @Test
  void deletesIssueAgentSessionByBindingAndKeepsIssueIntact() {
    // Issue+Agent 的 Session 只能由稳定绑定 Thread 解析：归属行先于 harness 行删除，Issue/Project 事实保持完整。
    String agentName = agentDefinition();
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    UUID sessionId = seedSession("issue-agent-session");
    UUID root = seedRootThread(sessionId, ThreadExecutionControl.RUNNABLE);
    UUID child = seedChildThread(sessionId, root, ThreadExecutionControl.RUNNABLE);
    bindIssueAgentThread(issueId, agentName, child);
    UUID join = seedJoin(child, root, 1L, null, null);

    deleteSessions(ProjectSessionOwnership.owner(issueId, agentName));

    assertEquals(0L, sessionRows(sessionId));
    assertEquals(0L, entryRows(sessionId));
    assertEquals(0L, threadRows(root));
    assertEquals(0L, threadRows(child));
    assertEquals(0L, joinRows(join));
    assertEquals(
        0L, count("select count(*) from project_issue_agent_thread where issue_id = ?", issueId));
    assertEquals(1L, count("select count(*) from project_issue where id = ?", issueId));
    assertEquals(1L, count("select count(*) from project where id = ?", projectId));
  }

  @Test
  void runReferenceRollsBackProjectOwnershipDeletion() {
    // 真实 FK 阻止绑定删除时，产品归属和 runtime 事实必须在同一事务中保留。
    String agentName = agentDefinition();
    UUID projectId = projectRow();
    UUID issueId = issueRow(projectId);
    UUID sessionId = seedSession("run-bound");
    UUID threadId = seedRootThread(sessionId, ThreadExecutionControl.RUNNABLE);
    UUID rootEntryId = headEntryOf(sessionId);
    bindIssueAgentThread(issueId, agentName, threadId);
    jdbc.update(
        "insert into project_issue_stage_budget (issue_id, state, max_runs, budget_after_ordinal)"
            + " values (?, 'DESIGN', 3, 0)",
        issueId);
    jdbc.update(
        "insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id,"
            + " status, start_entry_id, remaining_execution_ms, active_since)"
            + " values (?, ?, 1, 'DESIGN', ?, ?, 'RUNNING', ?, 60000, current_timestamp)",
        UUID.randomUUID(),
        issueId,
        sessionId,
        threadId,
        rootEntryId);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> deleteSessions(ProjectSessionOwnership.owner(issueId, agentName)));
    assertEquals(1L, sessionRows(sessionId));
    assertEquals(1L, entryRows(sessionId));
    assertEquals(1L, threadRows(threadId));
    assertEquals(
        1L, count("select count(*) from project_issue_agent_thread where issue_id = ?", issueId));
    assertEquals(1L, count("select count(*) from project_issue_run where issue_id = ?", issueId));
  }

  /** 与生产调用方一致：深删除在调用方的事务内执行，失败整体回滚。 */
  private void deleteSessions(SessionOwner owner) {
    transactions.executeWithoutResult(status -> orchestrator.deleteSessionsByOwner(owner));
  }

  // ---------- fixtures ----------

  private UUID seedSession(String name) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, name, T0));
          return sessionId;
        });
  }

  private UUID seedRootThread(UUID sessionId, ThreadExecutionControl status) {
    UUID rootEntryId = seedRootEntry(sessionId);
    return seedThreadIn(sessionId, rootEntryId, null, status);
  }

  /** 在 Session 内新建 ROOT Entry；每个 Session 至多一个（schema 唯一约束）。 */
  private UUID seedRootEntry(UUID sessionId) {
    return store.transaction(
        tx -> {
          UUID rootEntryId = tx.nextId();
          tx.entries()
              .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), T0));
          return rootEntryId;
        });
  }

  private UUID seedChildThread(UUID sessionId, UUID parentThreadId, ThreadExecutionControl status) {
    return seedThreadIn(sessionId, headEntryOf(sessionId), parentThreadId, status);
  }

  private UUID seedThreadIn(
      UUID sessionId, UUID headEntryId, UUID parentThreadId, ThreadExecutionControl status) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          // 根线程使用独立开关；子线程恒 FOLLOW 传入的执行根（fixture 的 parent 即执行根）。
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      parentThreadId,
                      headEntryId,
                      HASH,
                      "thread-" + threadId,
                      parentThreadId == null ? threadId : parentThreadId,
                      false,
                      SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
                      status,
                      0L,
                      1L,
                      0L,
                      T0,
                      T0));
          return threadId;
        });
  }

  /** Session 内已有线程的 head Entry（同 Session 的合法 head，也用作 matched receipt 的结果 head）。 */
  private UUID headEntryOf(UUID sessionId) {
    return store.transaction(
        tx ->
            tx.threads().listBySession(sessionId).stream()
                .map(ThreadState::headEntryId)
                .findFirst()
                .orElseThrow());
  }

  /**
   * 写入一条 join 及其源 command（同一事务，满足 join 对源 command 的外键）。
   *
   * <p>{@code terminalEntryId} 为空表示未匹配的 pending join；非空表示已匹配未交付（交付序列为空，且需要父命令，故本 fixture 只构造未交付形态）。
   *
   * @return 该 join 的 invocationId，便于按行断言删除结果
   */
  private UUID seedJoin(
      UUID childThreadId,
      UUID parentThreadId,
      long sequence,
      UUID terminalEntryId,
      UUID finalAnswerEntryId) {
    UUID invocationId = UUID.randomUUID();
    store.transaction(
        tx -> {
          List<UUID> threadsToLock =
              terminalEntryId != null
                  ? Stream.of(childThreadId, parentThreadId).sorted(UuidOrder.COMPARATOR).toList()
                  : List.of(childThreadId);
          threadsToLock.forEach(id -> tx.threads().lock(id));
          tx.commands().insert(List.of(command(childThreadId, sequence)));
          if (terminalEntryId != null) {
            tx.commands().insert(List.of(command(parentThreadId, 1L)));
          }
          ThreadJoin join =
              new ThreadJoin(
                  invocationId,
                  HASH,
                  parentThreadId,
                  childThreadId,
                  sequence,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0,
                  JoinDelivery.NOTIFY_PARENT,
                  null);
          tx.joins().insert(join);
          if (terminalEntryId != null) {
            tx.joins()
                .update(join.match(terminalEntryId, finalAnswerEntryId, T1).delivered(1L, T1));
          }
          return null;
        });
    return invocationId;
  }

  private static ThreadCommand command(UUID threadId, long sequence) {
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent("message " + sequence))));
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        UUID.randomUUID(),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        T0);
  }

  // ---------- row probes ----------

  private long sessionRows(UUID sessionId) {
    return count("select count(*) from harness_session where id = ?", sessionId);
  }

  private long entryRows(UUID sessionId) {
    return count("select count(*) from harness_entry where session_id = ?", sessionId);
  }

  private long threadRows(UUID threadId) {
    return count("select count(*) from harness_thread where id = ?", threadId);
  }

  private long joinRows(UUID invocationId) {
    return count("select count(*) from harness_thread_join where invocation_id = ?", invocationId);
  }
}
