package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.ASK_USER_QUESTIONNAIRE;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.askUserBinding;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.askUserRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.askUserResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.hostBinding;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolCall;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * 事务内 Harness 失效通知的 PostgreSQL 集成验证：走真实生产写入口，断言提交后才投递的内建 {@code pg_notify}。
 *
 * <p>通知由 {@code PostgresqlHarnessTransaction} 在真实写事务内发布；每条断言都只能由生产写路径自己发布的通知满足。
 */
class PostgresqlHarnessTransactionNotificationTest {

  private static final String VERSION_CHANNEL = "harness_thread_version";
  private static final String TREE_CHANNEL = "harness_thread_tree";
  private static final String INTERACTION_CHANNEL = "harness_tool_interaction";
  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private HarnessStore store;
  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    dataSource = PostgresqlHarnessStoreFixture.dataSource();
  }

  /**
   * 测试意图：Thread 插入与真实 version 变化在同一位置发布 version + tree 两条失效信号，且 tree payload 是沿不可变祖先链解析的 真实执行根；同
   * version 的 exact replay 静默。
   */
  @Test
  void threadInsertAndRealVersionChangePublishVersionAndTreeAtTrueRoot() throws SQLException {
    SessionRoot root = seedSessionRoot(store);
    ToolChain rootChain = seedToolChain(store, root, null, null, ToolKind.BASH);
    ToolChain childChain =
        seedToolChain(store, root, rootChain.threadId(), rootChain.threadId(), ToolKind.BASH);
    ToolChain grandChildChain =
        seedToolChain(store, root, childChain.threadId(), rootChain.threadId(), ToolKind.BASH);

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      // 回滚不投递：同一写入口插入新 Thread 后回滚，任何 channel 都不得收到通知。
      assertThrows(
          IllegalStateException.class,
          () ->
              store.transaction(
                  tx -> {
                    tx.insertThread(
                        plainThread(
                            tx.nextId(),
                            root.sessionId(),
                            rootChain.threadId(),
                            root.rootEntryId()));
                    throw new IllegalStateException("rollback");
                  }));
      listener.assertSilent();

      // 插入子 Thread：version 为子自身，tree 为真实执行根。
      UUID insertedChild =
          store.transaction(
              tx -> {
                UUID id = tx.nextId();
                tx.insertThread(
                    plainThread(id, root.sessionId(), rootChain.threadId(), root.rootEntryId()));
                return id;
              });
      listener.expectExactly(
          versionNotification(insertedChild, 1L), treeNotification(rootChain.threadId()));

      // 同 version exact replay 不视为变化，保持静默。
      inTransaction(
          store,
          tx -> {
            tx.lockThread(rootChain.threadId()).orElseThrow();
            ThreadState stored = tx.findThread(rootChain.threadId()).orElseThrow();
            tx.updateThread(stored);
          });
      listener.assertSilent();

      // 孙 Thread 真实 version 变化：version 为孙自身，tree 仍为真实执行根。
      inTransaction(
          store,
          tx -> {
            tx.lockThread(grandChildChain.threadId()).orElseThrow();
            ThreadState stored = tx.findThread(grandChildChain.threadId()).orElseThrow();
            tx.updateThread(stored.reserveCommandSequences(1, T4));
          });
      listener.expectExactly(
          versionNotification(grandChildChain.threadId(), 2L),
          treeNotification(rootChain.threadId()));
    }
  }

  /** 测试意图：审批等待的进入、等待态内重写、离开等待与删除等待调用都按真实执行根失效交互列表。 */
  @Test
  void toolWaitingApprovalTransitionsAndWaitingDeletionPublishInteractionAtTrueRoot()
      throws SQLException {
    SessionRoot root = seedSessionRoot(store);
    ToolChain rootChain = seedToolChain(store, root, null, null, ToolKind.BASH);
    ToolChain childChain =
        seedToolChain(store, root, rootChain.threadId(), rootChain.threadId(), ToolKind.BASH);
    ToolChain grandChildChain =
        seedToolChain(store, root, childChain.threadId(), rootChain.threadId(), ToolKind.BASH);
    ToolChain deletionChain =
        seedToolChain(store, root, rootChain.threadId(), rootChain.threadId(), ToolKind.BASH);
    UUID interactionRoot = rootChain.threadId();

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      // READY -> WAITING_APPROVAL
      updateTool(
          store, grandChildChain.toolId(), tool -> tool.requestApproval("needs approval", T2));
      listener.expectExactly(interactionNotification(interactionRoot));

      // 等待态内重写（精确 replay）仍属等待态，继续失效。
      updateTool(store, grandChildChain.toolId(), tool -> tool);
      listener.expectExactly(interactionNotification(interactionRoot));

      // WAITING_APPROVAL -> READY（审批通过）离开等待。
      updateTool(
          store,
          grandChildChain.toolId(),
          tool ->
              tool.decideApproval(
                  ToolApprovalDecision.ALLOWED, UUID.randomUUID(), "tester", "ok", T3, T3));
      listener.expectExactly(interactionNotification(interactionRoot));

      // 删除等待调用：删除前解析真实执行根，且绝不伪造 version。
      updateTool(store, deletionChain.toolId(), tool -> tool.requestApproval("needs approval", T3));
      listener.expectExactly(interactionNotification(interactionRoot));
      inTransaction(
          store,
          tx -> {
            tx.lockToolInvocation(deletionChain.toolId()).orElseThrow();
            assertEquals(1, tx.deleteToolInvocationsByIds(List.of(deletionChain.toolId())));
          });
      listener.expectExactly(interactionNotification(interactionRoot));
    }
  }

  /** 测试意图：人工输入等待（WAITING_INPUT）的进入、等待态内重写与离开都要失效交互列表。 */
  @Test
  void toolWaitingInputTransitionsPublishInteractionAtTrueRoot() throws SQLException {
    SessionRoot root = seedSessionRoot(store);
    ToolChain askChain = seedToolChain(store, root, null, null, ToolKind.ASK_USER);

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      updateTool(store, askChain.toolId(), tool -> tool.requestInput(T2));
      listener.expectExactly(interactionNotification(askChain.threadId()));

      updateTool(store, askChain.toolId(), tool -> tool);
      listener.expectExactly(interactionNotification(askChain.threadId()));

      updateTool(
          store,
          askChain.toolId(),
          tool -> tool.cancel(new ToolInvocationError("CANCELLED", "stopped"), T3));
      listener.expectExactly(interactionNotification(askChain.threadId()));
    }
  }

  /** 测试意图：普通非等待调用变化（not-required 审批、发起派发、进入执行）不得冒充人工交互失效。 */
  @Test
  void nonWaitingToolInvocationUpdatesStaySilent() throws SQLException {
    SessionRoot root = seedSessionRoot(store);
    ToolChain chain = seedToolChain(store, root, null, null, ToolKind.BASH);

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      updateTool(store, chain.toolId(), tool -> tool.markApprovalNotRequired(T2));
      listener.assertSilent();
      updateTool(store, chain.toolId(), tool -> tool.beginDispatch(T3));
      listener.assertSilent();
      updateTool(store, chain.toolId(), tool -> tool.markRunning(T4));
      listener.assertSilent();
    }
  }

  /** 测试意图：删除 Thread 时按删除前解析的真实执行根在原 tree channel 定点失效（子/孙变化归到真根），且绝不合成已不存在 Thread 的 version。 */
  @Test
  void threadDeletionPublishesTargetedTreeRootWithoutVersionNotification() throws SQLException {
    SessionRoot root = seedSessionRoot(store);
    ToolChain rootChain = seedToolChain(store, root, null, null, ToolKind.BASH);
    ToolChain childChain =
        seedToolChain(store, root, rootChain.threadId(), rootChain.threadId(), ToolKind.BASH);
    ToolChain grandChildChain =
        seedToolChain(store, root, childChain.threadId(), rootChain.threadId(), ToolKind.BASH);

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      // 删除 child + grandchild，存活 root 是受影响真根；删除的 READY 调用不产生交互失效。
      int deletedChildren =
          store.transaction(
              tx -> {
                tx.lockTree(rootChain.threadId());
                tx.lockThread(childChain.threadId()).orElseThrow();
                tx.lockThread(grandChildChain.threadId()).orElseThrow();
                return tx.deleteThreads(List.of(childChain.threadId(), grandChildChain.threadId()));
              });
      assertEquals(2, deletedChildren);
      listener.expectExactly(treeNotification(rootChain.threadId()));

      // 整棵树（含根）删除：删除前取得根身份，删除后仍定点失效该根，但不发 version。
      int deletedRoot =
          store.transaction(
              tx -> {
                tx.lockTree(rootChain.threadId());
                tx.lockThread(rootChain.threadId()).orElseThrow();
                return tx.deleteThreads(List.of(rootChain.threadId()));
              });
      assertEquals(1, deletedRoot);
      listener.expectExactly(treeNotification(rootChain.threadId()));
    }
  }

  /** 测试意图：未提交的写入对 LISTEN 连接不可见，提交后才投递（NOTIFY 只在提交时发送）。 */
  @Test
  void uncommittedThreadWriteIsInvisibleUntilCommit() throws Exception {
    SessionRoot root = seedSessionRoot(store);
    ToolChain rootChain = seedToolChain(store, root, null, null, ToolKind.BASH);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      Future<UUID> future =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        UUID id = tx.nextId();
                        tx.insertThread(
                            plainThread(
                                id, root.sessionId(), rootChain.threadId(), root.rootEntryId()));
                        written.countDown();
                        await(release);
                        return id;
                      }));
      assertTrue(written.await(10, TimeUnit.SECONDS));
      // 事务已写入但未提交：通知必须不可见。
      listener.assertSilent();
      release.countDown();
      UUID childId = future.get(10, TimeUnit.SECONDS);
      listener.expectExactly(
          versionNotification(childId, 1L), treeNotification(rootChain.threadId()));
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * 测试意图：冻结了 required_environment_id 的 TOOL Work 在 create/reset/claim/cleanup 边界必须失效待处理读模型，使「环境离线后
   * 才产生的新调用」在查询中可见；payload 复用 interaction channel 的真实执行根。
   */
  @Test
  void environmentBoundToolWorkBoundariesPublishInteractionAtTrueRoot() throws SQLException {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID node = UUID.randomUUID();
    seedEnvironmentConnection(jdbc, ENVIRONMENT, node);
    SessionRoot root = seedSessionRoot(store);
    ToolChain chain = seedToolChain(store, root, null, null, ToolKind.BASH);
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, chain.toolId());

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      requestEnvironmentWork(store, chain.threadId(), target, ENVIRONMENT);
      listener.expectExactly(interactionNotification(chain.threadId()));

      // reset（重复唤醒）仍改变待领取事实。
      requestEnvironmentWork(store, chain.threadId(), target, ENVIRONMENT);
      listener.expectExactly(interactionNotification(chain.threadId()));

      Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
      ClaimedWork claim =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.TOOL, now, "lease-1", Duration.ofSeconds(60), node))
              .orElseThrow();
      listener.expectExactly(interactionNotification(chain.threadId()));

      // cleanup/reset：重排清租约使 Work 可能重新进入待领取。
      inTransaction(store, tx -> tx.rescheduleWork(claim, now, Duration.ZERO));
      listener.expectExactly(interactionNotification(chain.threadId()));

      ClaimedWork reclaimed =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.TOOL, now, "lease-2", Duration.ofSeconds(60), node))
              .orElseThrow();
      listener.expectExactly(interactionNotification(chain.threadId()));

      // cleanup：终态删除。
      store.transaction(tx -> tx.completeWork(reclaimed, now));
      listener.expectExactly(interactionNotification(chain.threadId()));

      requestEnvironmentWork(store, chain.threadId(), target, ENVIRONMENT);
      listener.expectExactly(interactionNotification(chain.threadId()));

      inTransaction(
          store,
          tx -> {
            tx.lockThread(chain.threadId()).orElseThrow();
            assertTrue(tx.deleteWork(target));
          });
      listener.expectExactly(interactionNotification(chain.threadId()));
    }
  }

  /** 测试意图：未冻结环境亲和性的 Work 变化不产生环境等待失效，避免把普通唤醒误标成人工等待。 */
  @Test
  void workWithoutEnvironmentAffinityStaysSilentOnInteractionChannel() throws SQLException {
    SessionRoot root = seedSessionRoot(store);
    ToolChain chain = seedToolChain(store, root, null, null, ToolKind.BASH);
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, chain.toolId());

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      inTransaction(
          store,
          tx -> {
            tx.lockThread(chain.threadId()).orElseThrow();
            tx.requestWork(target, T2);
          });
      listener.assertSilent();

      Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
      ClaimedWork claim =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(WorkTargetType.TOOL, now, "lease-1", Duration.ofSeconds(60)))
              .orElseThrow();
      listener.assertSilent();

      store.transaction(tx -> tx.completeWork(claim, now));
      listener.assertSilent();
    }
  }

  /**
   * 测试意图：环境等待的来源工具调用已被清理（合法清理先删调用再删 Work）时，Work 终态仍必须失效待处理读模型；此时已无法解析真实执行根， 按既有协议发空 payload，由
   * listener 全量 resync。
   */
  @Test
  void environmentWorkInvalidationResyncsWhenToolSourceIsAlreadyDeleted() throws SQLException {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID node = UUID.randomUUID();
    seedEnvironmentConnection(jdbc, ENVIRONMENT, node);
    SessionRoot root = seedSessionRoot(store);
    ToolChain chain = seedToolChain(store, root, null, null, ToolKind.BASH);
    WorkTarget target = new WorkTarget(WorkTargetType.TOOL, chain.toolId());

    try (InvalidationListener listener = InvalidationListener.open(dataSource)) {
      requestEnvironmentWork(store, chain.threadId(), target, ENVIRONMENT);
      listener.expectExactly(interactionNotification(chain.threadId()));

      Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
      ClaimedWork claim =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.TOOL, now, "lease-1", Duration.ofSeconds(60), node))
              .orElseThrow();
      listener.expectExactly(interactionNotification(chain.threadId()));

      // 清理来源调用：READY 调用删除本身不属等待态，保持静默。
      inTransaction(
          store,
          tx -> {
            tx.lockToolInvocation(chain.toolId()).orElseThrow();
            assertEquals(1, tx.deleteToolInvocationsByIds(List.of(chain.toolId())));
          });
      listener.assertSilent();

      // 来源 Thread 已不可解析：Work 终态失效退化为空 payload 全量 resync。
      store.transaction(tx -> tx.completeWork(claim, now));
      listener.expectExactly(interactionResyncNotification());
    }
  }

  // ---------------- fixture ----------------

  private record SessionRoot(UUID sessionId, UUID rootEntryId) {}

  private record ToolChain(UUID turnStartId, UUID threadId, UUID toolId) {}

  private record ToolSpec(
      ModelRequestSpec request, ProviderResponse response, ToolCall call, ToolBinding binding) {}

  private enum ToolKind {
    BASH,
    ASK_USER
  }

  /** 新建独立 Session 与其 ROOT Entry，作为执行树根链的起点。 */
  private static SessionRoot seedSessionRoot(HarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          return new SessionRoot(sessionId, rootEntryId);
        });
  }

  /**
   * 在既有 Session 内新建 Thread 并挂上 TURN_START -> USER -> ASSISTANT 链、终止模型与单个 READY 工具调用。
   *
   * <p>{@code parentThreadId} 为 null 时创建执行根；否则父 Thread 已提交，本事务先锁父再加子 Thread 锁，符合同阶升序加锁约束。
   */
  private static ToolChain seedToolChain(
      HarnessStore store, SessionRoot root, UUID parentThreadId, UUID rootThreadId, ToolKind kind) {
    ToolSpec spec = toolSpec(kind);
    return store.transaction(
        tx -> {
          UUID sessionId = root.sessionId();
          UUID turnStartId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID modelId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID toolId = tx.nextId();
          if (parentThreadId != null) {
            tx.lockThread(parentThreadId).orElseThrow();
          }
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  sessionId,
                  root.rootEntryId(),
                  resolvedTurnStartPayload(threadId),
                  T1));
          tx.insertEntry(new Entry(userEntryId, sessionId, turnStartId, userMessagePayload(), T1));
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  sessionId,
                  userEntryId,
                  mappedAssistant(spec.request(), spec.response()),
                  T1));
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  parentThreadId,
                  turnStartId,
                  CREATION_REQUEST_HASH,
                  "main",
                  parentThreadId == null
                      ? ThreadYoloPolicy.root(false)
                      : ThreadYoloPolicy.follow(rootThreadId),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  1L,
                  T0,
                  T0));
          tx.lockThread(threadId).orElseThrow();
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  threadId,
                  turnStartId,
                  turnStartId,
                  spec.request(),
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  T1,
                  T1));
          ModelInvocation model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.beginDispatch(T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.markRunning(T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.succeed(spec.response(), T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.attachResultEntry(assistantEntryId, T1));
          tx.insertToolInvocations(
              List.of(
                  new ToolInvocation(
                      toolId,
                      modelId,
                      assistantEntryId,
                      0,
                      spec.call(),
                      spec.binding(),
                      ToolInvocationStatus.READY,
                      0,
                      null,
                      null,
                      ToolEffectBatch.EMPTY,
                      null,
                      T1,
                      T1)));
          return new ToolChain(turnStartId, threadId, toolId);
        });
  }

  private static ToolSpec toolSpec(ToolKind kind) {
    if (kind == ToolKind.BASH) {
      return new ToolSpec(
          succeededRequest(), assistantResponse("call-1"), toolCall("call-1"), hostBinding());
    }
    return new ToolSpec(
        askUserRequest(),
        askUserResponse("call-1"),
        new ToolCall("call-1", "ask_user", ASK_USER_QUESTIONNAIRE),
        askUserBinding());
  }

  /** 只插入一个 Thread 行（head 指向既有 Entry），用于观察纯 Thread 写入口的失效信号。 */
  private static ThreadState plainThread(
      UUID threadId, UUID sessionId, UUID parentThreadId, UUID headEntryId) {
    return new ThreadState(
        threadId,
        sessionId,
        parentThreadId,
        headEntryId,
        CREATION_REQUEST_HASH,
        "child",
        ThreadYoloPolicy.follow(parentThreadId),
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        1L,
        T0,
        T0);
  }

  private static ToolInvocation updateTool(
      HarnessStore store, UUID toolId, UnaryOperator<ToolInvocation> transition) {
    return store.transaction(
        tx -> {
          ToolInvocation current = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation next = transition.apply(current);
          tx.updateToolInvocations(List.of(next));
          return next;
        });
  }

  private static void requestEnvironmentWork(
      HarnessStore store, UUID threadId, WorkTarget target, EnvironmentId environment) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(target, T2, environment);
        });
  }

  private static String versionNotification(UUID threadId, long version) {
    return VERSION_CHANNEL + "|" + threadId + ":" + version;
  }

  private static String treeNotification(UUID rootThreadId) {
    return TREE_CHANNEL + "|" + rootThreadId;
  }

  private static String interactionNotification(UUID rootThreadId) {
    return INTERACTION_CHANNEL + "|" + rootThreadId;
  }

  /** 来源 Thread 已不可解析时的空 payload：listener 依此回退全量 resync。 */
  private static String interactionResyncNotification() {
    return INTERACTION_CHANNEL + "|";
  }

  private static void seedEnvironmentConnection(
      JdbcTemplate jdbc, EnvironmentId environment, UUID node) {
    jdbc.update(
        """
        insert into environment (id, name, registration_token, version)
        values (?, ?, ?, 0)
        on conflict (id) do nothing
        """,
        environment.value(),
        "env-" + environment.value().toString().substring(0, 8),
        "token-" + environment.value());
    jdbc.update(
        """
        insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until)
        values (?, ?, gen_random_uuid(), 'READY', '{}'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds')
        on conflict (environment_id) do update
        set owner_node_id = excluded.owner_node_id,
            status = excluded.status,
            runtime_info = excluded.runtime_info,
            last_seen_at = excluded.last_seen_at,
            lease_until = excluded.lease_until
        """,
        environment.value(),
        node);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch wait timed out");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("latch wait interrupted", error);
    }
  }

  /** LISTEN 三个 Harness 失效 channel 的独立连接，按 {@code channel|payload} 收集提交后投递的通知。 */
  private static final class InvalidationListener implements AutoCloseable {

    private final Connection connection;
    private final PGConnection notifications;

    private InvalidationListener(Connection connection, PGConnection notifications) {
      this.connection = connection;
      this.notifications = notifications;
    }

    static InvalidationListener open(DataSource dataSource) throws SQLException {
      Connection connection = dataSource.getConnection();
      connection.setAutoCommit(true);
      try (Statement statement = connection.createStatement()) {
        statement.execute("LISTEN " + VERSION_CHANNEL);
        statement.execute("LISTEN " + TREE_CHANNEL);
        statement.execute("LISTEN " + INTERACTION_CHANNEL);
      } catch (SQLException error) {
        connection.close();
        throw error;
      }
      return new InvalidationListener(connection, connection.unwrap(PGConnection.class));
    }

    /** 断言收到且只收到给定通知集合（顺序无关）。 */
    void expectExactly(String... expected) throws SQLException {
      Set<String> expectedSet = Set.of(expected);
      Set<String> actual = new LinkedHashSet<>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline && !actual.containsAll(expectedSet)) {
        drain(actual, 500);
      }
      // 期望集合已满足后再短促排空一次，避免同一提交的后续消息落入下一次断言。
      drain(actual, 200);
      assertEquals(expectedSet, actual, "unexpected Harness notification set");
    }

    /** 断言在等待窗口内没有收到任何通知。 */
    void assertSilent() throws SQLException {
      Set<String> actual = new LinkedHashSet<>();
      drain(actual, 500);
      assertTrue(actual.isEmpty(), () -> "expected no notification but received " + actual);
    }

    private void drain(Set<String> sink, int timeoutMillis) throws SQLException {
      PGNotification[] batch = notifications.getNotifications(timeoutMillis);
      if (batch != null) {
        for (PGNotification notification : batch) {
          sink.add(notification.getName() + "|" + notification.getParameter());
        }
      }
    }

    @Override
    public void close() throws SQLException {
      connection.close();
    }
  }
}
