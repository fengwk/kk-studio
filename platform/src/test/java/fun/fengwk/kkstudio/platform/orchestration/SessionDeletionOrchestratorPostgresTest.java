package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * owner 深删除在真实 PostgreSQL 与外键约束下的契约：跨 Session 执行树整体删除、pending join 一并回收、生存者零伤害。
 *
 * <p>测试意图（断言真实 store 中的行数，不依赖 mock 调用顺序）：
 *
 * <ul>
 *   <li>归属 owner 的所有 Session/Thread（含 ACTIVE 与已停下的 IDLE）随 task 子 Session 与共享 Session 内的无父 fork
 *       一起删除；未匹配的 pending join 与「已匹配未交付」的 join 因父子两端都在删除集合内而一并删除；
 *   <li>不属于该 owner 的 Session/Thread/Join/Command 完全不受影响（防全量删除破坏生存者）；
 *   <li>闭包被截断（待删 Thread 的执行父仍在生存 Session 中）时必须整体回滚，绝不留下悬挂 join 或半删子树；
 *   <li>owner 未持有任何 Thread 的空 Session 同样可被深删除；
 *   <li>Issue+Agent 归属：Session 由稳定绑定 Thread 解析，归属行（FK RESTRICT）先于 harness Thread/Session 删除，Issue
 *       自身不受影响。
 * </ul>
 *
 * <p>本测试用真实 {@link PostgresqlHarnessStore} 与真实 PostgreSQL 外键路径驱动编排器：不覆盖任何全局 store bean（platform
 * 测试上下文已在扫描期注册占位 store），而是就地构造一个只在本测试内使用的 store 实例，并由测试提供外层事务——与生产调用方 （{@code @Transactional}
 * 入口）的语义完全一致，blob ref 的 MANDATORY 事务要求因此成立。
 */
class SessionDeletionOrchestratorPostgresTest extends OwnerTestSupport {

  private static final Instant T0 = Instant.ofEpochMilli(1000);
  private static final Instant T1 = Instant.ofEpochMilli(2000);
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ChatSessionRepository chatSessionRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueAgentThreadRepository issueAgentThreadRepository;
  @Autowired private SessionBlobRefManager refManager;
  @Autowired private NotificationBus notificationBus;

  private HarnessStore store;
  private SessionDeletionOrchestrator orchestrator;
  private TransactionTemplate transactions;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUpOrchestrator() {
    store =
        new PostgresqlHarnessStore(
            dataSource, transactionManager, UUID::randomUUID, notificationBus);
    ObjectProvider<HarnessStore> stores = mock(ObjectProvider.class);
    when(stores.getIfAvailable()).thenReturn(store);
    orchestrator =
        new SessionDeletionOrchestrator(
            chatSessionRepository,
            chatRepository,
            projectRepository,
            issueRepository,
            issueAgentThreadRepository,
            stores,
            refManager);
    transactions = new TransactionTemplate(transactionManager);
  }

  @Test
  void deletesWholeExecutionTreeAcrossSessionsAndKeepsUnrelatedOwnerIntact() {
    UUID owner = chatOwner();
    UUID otherOwner = chatOwner();
    UUID ownedSession = ownedSession("owned", owner);
    UUID ownedRoot = seedRootThread(ownedSession, ThreadExecutionControl.RUNNABLE);
    UUID sameSessionChild =
        seedChildThread(ownedSession, ownedRoot, ThreadExecutionControl.RUNNABLE);
    UUID childSession = seedSession("task-child");
    UUID childSessionRoot = seedRootEntry(childSession);
    UUID crossSessionChild =
        seedThreadIn(childSession, childSessionRoot, ownedRoot, ThreadExecutionControl.RUNNABLE);
    // 同一 Session 内由另一入口建立的无父 fork：只有「以 Session 为单位」推进闭包才会把它一并删除。
    UUID childSessionFork =
        seedThreadIn(childSession, childSessionRoot, null, ThreadExecutionControl.RUNNABLE);
    UUID emptySession = ownedSession("owned-empty", owner);
    // pending join（未匹配）：逐 child 删除会拒绝，闭包内两端同删必须允许。
    UUID pendingJoin = seedJoin(sameSessionChild, ownedRoot, 1L, null, null);
    // 已匹配未交付：父仍在删除集合内，同样一并回收。
    UUID matchedPendingDelivery =
        seedJoin(sameSessionChild, ownedRoot, 2L, headEntryOf(ownedSession), null);
    // 跨 Session 子线程持有的 pending join：其 Session 由后代闭包覆盖。
    UUID crossSessionJoin = seedJoin(crossSessionChild, ownedRoot, 1L, null, null);

    // 生存者：另一个 owner 的 Session + root + child + pending join。
    UUID survivorSession = ownedSession("survivor", otherOwner);
    UUID survivorRoot = seedRootThread(survivorSession, ThreadExecutionControl.RUNNABLE);
    UUID survivorChild =
        seedChildThread(survivorSession, survivorRoot, ThreadExecutionControl.RUNNABLE);
    UUID survivorJoin = seedJoin(survivorChild, survivorRoot, 1L, null, null);

    deleteSessions(new OwnerRef.Chat(owner));

    assertEquals(0L, sessionRows(ownedSession));
    assertEquals(0L, sessionRows(childSession));
    assertEquals(0L, sessionRows(emptySession));
    assertEquals(0L, entryRows(ownedSession));
    assertEquals(0L, entryRows(childSession));
    for (UUID threadId :
        List.of(ownedRoot, sameSessionChild, crossSessionChild, childSessionFork)) {
      assertEquals(0L, threadRows(threadId), "thread " + threadId + " must be deleted");
      assertEquals(0L, commandRows(threadId), "commands of " + threadId + " must be deleted");
    }
    assertEquals(0L, joinRows(pendingJoin));
    assertEquals(0L, joinRows(matchedPendingDelivery));
    assertEquals(0L, joinRows(crossSessionJoin));
    assertEquals(0L, count("select count(*) from chat_session where chat_id = ?", owner));

    // 生存侧：无关 owner 的整棵树（含 pending join 与命令）逐行保留。
    assertEquals(1L, sessionRows(survivorSession));
    assertEquals(1L, threadRows(survivorRoot));
    assertEquals(1L, threadRows(survivorChild));
    assertEquals(1L, joinRows(survivorJoin));
    assertEquals(1L, commandRows(survivorChild));
    assertEquals(1L, count("select count(*) from chat_session where chat_id = ?", otherOwner));
  }

  @Test
  void truncatedClosureRollsBackWithoutTouchingSurvivors() {
    UUID owner = chatOwner();
    UUID otherOwner = chatOwner();
    UUID survivorSession = ownedSession("survivor", otherOwner);
    UUID survivorRoot = seedRootThread(survivorSession, ThreadExecutionControl.RUNNABLE);
    UUID ownedSession = ownedSession("owned", owner);
    // 该 Session 内的线程指向生存者 Session 的父：闭包只能是它自己，父不在删除集合内。
    UUID strandedChild =
        seedThreadIn(
            ownedSession,
            seedRootEntry(ownedSession),
            survivorRoot,
            ThreadExecutionControl.RUNNABLE);
    UUID strandedJoin = seedJoin(strandedChild, survivorRoot, 1L, null, null);

    assertThrows(IllegalStateException.class, () -> deleteSessions(new OwnerRef.Chat(owner)));

    // 全部回滚：归属行、Session、Thread、Join 都与删除前一致。
    assertEquals(1L, sessionRows(ownedSession));
    assertEquals(1L, threadRows(strandedChild));
    assertEquals(1L, joinRows(strandedJoin));
    assertEquals(1L, sessionRows(survivorSession));
    assertEquals(1L, threadRows(survivorRoot));
    assertEquals(1L, count("select count(*) from chat_session where chat_id = ?", owner));
  }

  @Test
  void deletesOwnedSessionWithoutThreads() {
    UUID owner = chatOwner();
    UUID emptySession = ownedSession("owned-empty", owner);

    deleteSessions(new OwnerRef.Chat(owner));

    assertEquals(0L, sessionRows(emptySession));
    assertEquals(0L, entryRows(emptySession));
    assertEquals(0L, count("select count(*) from chat_session where chat_id = ?", owner));
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

    deleteSessions(new OwnerRef.IssueAgent(issueId, agentName));

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

  /** 与生产调用方一致：深删除在调用方的事务内执行，失败整体回滚。 */
  private void deleteSessions(OwnerRef owner) {
    transactions.executeWithoutResult(status -> orchestrator.deleteSessionsByOwner(owner));
  }

  // ---------- fixtures ----------

  /** owner 持有的 Session：harness_session 行 + chat_session 归属行。 */
  private UUID ownedSession(String name, UUID owner) {
    UUID sessionId = seedSession(name);
    chatSessionRepository.insert(sessionId, owner);
    return sessionId;
  }

  private UUID seedSession(String name) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          tx.insertSession(new Session(sessionId, name, T0));
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
          tx.insertEntry(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), T0));
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
          ThreadYoloPolicy yoloPolicy =
              parentThreadId == null
                  ? ThreadYoloPolicy.root(false)
                  : ThreadYoloPolicy.follow(parentThreadId);
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  HASH,
                  "thread-" + threadId,
                  yoloPolicy,
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
            tx.listThreadsBySession(sessionId).stream()
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
          tx.lockThread(childThreadId);
          tx.insertCommands(List.of(command(childThreadId, sequence)));
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
                  T0);
          tx.insertJoin(join);
          if (terminalEntryId != null) {
            // insertJoin 只接受未匹配记录；匹配是首次冻结终态 Entry 的单调推进。
            tx.updateJoin(join.match(terminalEntryId, finalAnswerEntryId, T1));
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

  private long commandRows(UUID threadId) {
    return count("select count(*) from harness_thread_command where thread_id = ?", threadId);
  }

  private long joinRows(UUID invocationId) {
    return count("select count(*) from harness_thread_join where invocation_id = ?", invocationId);
  }
}
