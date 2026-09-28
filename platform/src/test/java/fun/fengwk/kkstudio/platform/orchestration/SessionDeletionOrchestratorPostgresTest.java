package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;

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
 *   <li>owner 未持有任何 Thread 的空 Session 同样可被深删除。
 * </ul>
 *
 * <p>本测试用真实 {@link PostgresqlHarnessStore} 覆盖测试上下文的占位 Store，让编排器走真实 Harness 外键路径；产品归属行经真实仓储写入。
 */
class SessionDeletionOrchestratorPostgresTest extends OwnerTestSupport {

  private static final Instant T0 = Instant.ofEpochMilli(1000);
  private static final Instant T1 = Instant.ofEpochMilli(2000);
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);

  /** 用真实 PostgreSQL store 覆盖测试占位 bean，使编排器遇到真实外键与行锁。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class RealHarnessStoreConfiguration {

    @Bean
    @Primary
    HarnessStore postgresqlHarnessStore(
        DataSource dataSource, PlatformTransactionManager transactionManager) {
      return new PostgresqlHarnessStore(dataSource, transactionManager, UUID::randomUUID);
    }
  }

  @Autowired private HarnessStore store;
  @Autowired private SessionDeletionOrchestrator orchestrator;
  @Autowired private ChatSessionRepository chatSessionRepository;

  @Test
  void deletesWholeExecutionTreeAcrossSessionsAndKeepsUnrelatedOwnerIntact() {
    UUID owner = chatOwner();
    UUID otherOwner = chatOwner();
    UUID ownedSession = ownedSession("owned", owner);
    UUID ownedRoot = seedRootThread(ownedSession, ThreadLifecycleStatus.ACTIVE);
    UUID sameSessionChild = seedChildThread(ownedSession, ownedRoot, ThreadLifecycleStatus.IDLE);
    UUID childSession = seedSession("task-child");
    UUID childSessionRoot = seedRootEntry(childSession);
    UUID crossSessionChild =
        seedThreadIn(childSession, childSessionRoot, ownedRoot, ThreadLifecycleStatus.IDLE);
    // 同一 Session 内由另一入口建立的无父 fork：只有「以 Session 为单位」推进闭包才会把它一并删除。
    UUID childSessionFork =
        seedThreadIn(childSession, childSessionRoot, null, ThreadLifecycleStatus.IDLE);
    UUID emptySession = ownedSession("owned-empty", owner);
    // pending join（未匹配）：逐 child 删除会拒绝，闭包内两端同删必须允许。
    UUID pendingJoin = seedJoin(sameSessionChild, ownedRoot, 1L, null, null);
    // 已匹配未交付：父仍在删除集合内，同样一并回收。
    UUID matchedPendingDelivery =
        seedJoin(sameSessionChild, ownedRoot, 2L, 1L, headEntryOf(ownedSession));
    // 跨 Session 子线程持有的 pending join：其 Session 由后代闭包覆盖。
    UUID crossSessionJoin = seedJoin(crossSessionChild, ownedRoot, 1L, null, null);

    // 生存者：另一个 owner 的 Session + root + child + pending join。
    UUID survivorSession = ownedSession("survivor", otherOwner);
    UUID survivorRoot = seedRootThread(survivorSession, ThreadLifecycleStatus.ACTIVE);
    UUID survivorChild = seedChildThread(survivorSession, survivorRoot, ThreadLifecycleStatus.IDLE);
    UUID survivorJoin = seedJoin(survivorChild, survivorRoot, 1L, null, null);

    orchestrator.deleteSessionsByOwner(new OwnerRef(OwnerType.CHAT, owner));

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
    assertEquals(0L, count("select count(*) from session_owner where chat_id = ?", owner));

    // 生存侧：无关 owner 的整棵树（含 pending join 与命令）逐行保留。
    assertEquals(1L, sessionRows(survivorSession));
    assertEquals(1L, threadRows(survivorRoot));
    assertEquals(1L, threadRows(survivorChild));
    assertEquals(1L, joinRows(survivorJoin));
    assertEquals(1L, commandRows(survivorChild));
    assertEquals(1L, count("select count(*) from session_owner where chat_id = ?", otherOwner));
  }

  @Test
  void truncatedClosureRollsBackWithoutTouchingSurvivors() {
    UUID owner = chatOwner();
    UUID otherOwner = chatOwner();
    UUID survivorSession = ownedSession("survivor", otherOwner);
    UUID survivorRoot = seedRootThread(survivorSession, ThreadLifecycleStatus.ACTIVE);
    UUID ownedSession = ownedSession("owned", owner);
    // 该 Session 内的线程指向生存者 Session 的父：闭包只能是它自己，父不在删除集合内。
    UUID strandedChild =
        seedThreadIn(
            ownedSession, seedRootEntry(ownedSession), survivorRoot, ThreadLifecycleStatus.IDLE);
    UUID strandedJoin = seedJoin(strandedChild, survivorRoot, 1L, null, null);

    assertThrows(
        IllegalStateException.class,
        () -> orchestrator.deleteSessionsByOwner(new OwnerRef(OwnerType.CHAT, owner)));

    // 全部回滚：归属行、Session、Thread、Join 都与删除前一致。
    assertEquals(1L, sessionRows(ownedSession));
    assertEquals(1L, threadRows(strandedChild));
    assertEquals(1L, joinRows(strandedJoin));
    assertEquals(1L, sessionRows(survivorSession));
    assertEquals(1L, threadRows(survivorRoot));
    assertEquals(1L, count("select count(*) from session_owner where chat_id = ?", owner));
  }

  @Test
  void deletesOwnedSessionWithoutThreads() {
    UUID owner = chatOwner();
    UUID emptySession = ownedSession("owned-empty", owner);

    orchestrator.deleteSessionsByOwner(new OwnerRef(OwnerType.CHAT, owner));

    assertEquals(0L, sessionRows(emptySession));
    assertEquals(0L, entryRows(emptySession));
    assertEquals(0L, count("select count(*) from session_owner where chat_id = ?", owner));
  }

  // ---------- fixtures ----------

  /** owner 持有的 Session：harness_session 行 + session_owner 归属行。 */
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

  private UUID seedRootThread(UUID sessionId, ThreadLifecycleStatus status) {
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

  private UUID seedChildThread(UUID sessionId, UUID parentThreadId, ThreadLifecycleStatus status) {
    return seedThreadIn(sessionId, headEntryOf(sessionId), parentThreadId, status);
  }

  private UUID seedThreadIn(
      UUID sessionId, UUID headEntryId, UUID parentThreadId, ThreadLifecycleStatus status) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  HASH,
                  "thread-" + threadId,
                  false,
                  status,
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
   * <p>{@code matchedIdleVersion} 为空表示未匹配的 pending join；非空表示已匹配未交付（交付序列为空，且需要父命令，故本 fixture
   * 只构造未交付形态）。
   *
   * @return 该 join 的 invocationId，便于按行断言删除结果
   */
  private UUID seedJoin(
      UUID childThreadId,
      UUID parentThreadId,
      long sequence,
      Long matchedIdleVersion,
      UUID resultHeadEntryId) {
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
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0);
          tx.insertJoin(join);
          if (matchedIdleVersion != null) {
            // insertJoin 只接受未匹配记录；匹配是首次冻结结果 head 的单调推进。
            tx.updateJoin(join.match(matchedIdleVersion, resultHeadEntryId, T1));
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
