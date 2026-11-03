package fun.fengwk.kkstudio.platform.harness.dispatch;

import static fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.AGENT_LEASE;
import static fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.NOW;
import static fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.awaitTrue;
import static fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.config;
import static fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.newStore;
import static fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.seedThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.RecordingScheduler;
import fun.fengwk.kkstudio.platform.harness.dispatch.DispatcherTestSupport.ThreadSeed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** 集成测试：验证 {@link WorkTargetType#AGENT} Work 的 claim、环境亲和性过滤、Stop 围栏以及未注册处理器的跳过。 */
class HarnessAgentWorkIntegrationTest {

  private static final EnvironmentId ENV_A =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENV_B =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

  private final List<ExecutorService> ownedExecutors = new ArrayList<>();
  private final List<HarnessWorkDispatcher> ownedDispatchers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (HarnessWorkDispatcher dispatcher : ownedDispatchers) {
      try {
        dispatcher.stop();
      } catch (Exception ignored) {
      }
    }
    for (ExecutorService executor : ownedExecutors) {
      executor.shutdownNow();
    }
  }

  private ExecutorService singleThread() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ownedExecutors.add(executor);
    return executor;
  }

  /**
   * 1. AGENT Work Claim：当 Dispatcher 注册了 AGENT 处理器时，due 的 AGENT Work 会被成功 claim 并交付给
   * handler，租约时长匹配配置中的 agentLeaseDuration。
   */
  @Test
  void agentWorkClaimDeliveredToRegisteredProcessorWithConfiguredLease() {
    InMemoryHarnessStore store = newStore();
    ThreadSeed seed = seedThread(store);
    UUID threadId = seed.threadId();

    // 登记 AGENT Work
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.AGENT, threadId));
          return null;
        });

    AtomicReference<ClaimedWork> agentClaim = new AtomicReference<>();
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            store,
            config(1),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            Map.of(WorkTargetType.THREAD, claim -> {}, WorkTargetType.AGENT, agentClaim::set));
    ownedDispatchers.add(dispatcher);
    dispatcher.start();
    dispatcher.wake();

    awaitTrue(() -> agentClaim.get() != null);
    ClaimedWork claim = agentClaim.get();
    assertEquals(new WorkTarget(WorkTargetType.AGENT, threadId), claim.target());
    assertEquals(AGENT_LEASE, Duration.between(NOW, claim.leaseUntil()));
    assertNotNull(claim.leaseToken());
    assertEquals(1L, claim.claimedWakeVersion());

    // 确认持久存储中 Work 处于 active lease 状态
    store.transaction(
        tx -> {
          Work stored =
              tx.work().find(new WorkTarget(WorkTargetType.AGENT, threadId)).orElseThrow();
          assertEquals(claim.leaseToken(), stored.leaseToken());
          assertEquals(claim.leaseUntil(), stored.leaseUntil());
          return null;
        });
  }

  /** 2. 环境亲和性过滤：具有 requiredEnvironmentId 的 AGENT Work 只有在节点持有对应环境 ready 连接时才会被 claim。 */
  @Test
  void agentWorkAffinityFilteringRequiresReadyEnvironmentRoute() {
    InMemoryHarnessStore store = newStore();
    ThreadSeed seed = seedThread(store);
    UUID threadId = seed.threadId();

    Set<EnvironmentId> readyEnvs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    UUID nodeInstanceId = UUID.randomUUID();
    store.setRouteReadyPredicate(
        (nodeId, envId, now) -> nodeId.equals(nodeInstanceId) && readyEnvs.contains(envId));

    // 初始仅 ENV_B ready
    readyEnvs.add(ENV_B);

    // 登记绑定 ENV_A 的 AGENT Work
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.AGENT, threadId), ENV_A);
          return null;
        });

    AtomicReference<ClaimedWork> agentClaim = new AtomicReference<>();
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            nodeInstanceId,
            store,
            config(1),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            Map.of(WorkTargetType.THREAD, claim -> {}, WorkTargetType.AGENT, agentClaim::set));
    ownedDispatchers.add(dispatcher);
    dispatcher.start();
    dispatcher.wake();

    // ENV_A 未就绪，dispatcher 不应 claim 该 AGENT Work
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertNull(agentClaim.get());

    // 将 ENV_A 加入 ready，唤醒 dispatcher，成功 claim
    readyEnvs.add(ENV_A);
    dispatcher.wake();

    awaitTrue(() -> agentClaim.get() != null);
    assertEquals(new WorkTarget(WorkTargetType.AGENT, threadId), agentClaim.get().target());
    assertEquals(ENV_A, agentClaim.get().requiredEnvironmentId());
  }

  /** 3. Stop 围栏：StopControl 停止 Thread 时按规范顺序围栏并清理 AGENT Work 信箱；旧 lease token 无法推进状态。 */
  @Test
  void stopFencesAgentWorkInCanonicalOrderAndCancelsMailbox() {
    InMemoryHarnessStore store = newStore();
    ThreadSeed seed = seedThread(store);
    UUID threadId = seed.threadId();

    // 登记 AGENT Work
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.AGENT, threadId));
          return null;
        });

    // 模拟已被 claim 并持有有效 leaseToken
    ClaimedWork claim =
        store.transaction(
            tx ->
                tx.work()
                    .claimNext(WorkTargetType.AGENT, "test-agent-token", AGENT_LEASE, null)
                    .orElseThrow());
    assertEquals(new WorkTarget(WorkTargetType.AGENT, threadId), claim.target());

    // 种子 Thread 的执行选择是 builtin：以同一 id 注册 FakeAgentDriver，使 Stop 路由到它。
    DriverRegistry drivers =
        new DriverRegistry(List.of(new FakeAgentDriver(BuiltinExecution.DRIVER)));
    HarnessRuntime runtime = new HarnessRuntime(store, drivers, () -> CompactionConfig.DEFAULT);

    ThreadState initialThread = store.transaction(tx -> tx.threads().find(threadId).orElseThrow());
    StopResult result =
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), initialThread.version()));
    assertEquals(ThreadExecutionControl.STOPPED, result.thread().executionControl());

    // 验证 AGENT Work 已被 StopControl 净化（deleteWork），信箱已清除
    store.transaction(
        tx -> {
          assertTrue(tx.work().find(new WorkTarget(WorkTargetType.AGENT, threadId)).isEmpty());
          // 持有旧 leaseToken 的 claim 试图 lockClaimed 会被拒绝（返回 empty）
          assertTrue(tx.work().lockClaimed(claim).isEmpty());
          return null;
        });
  }

  /**
   * 4. 未注册 claim 跳过：Dispatcher 只注册 THREAD 处理器时（未注册 AGENT），即使存在 available 的 AGENT Work， round-robin
   * 轮转也绝不会 claim AGENT Work。
   */
  @Test
  void unregisteredAgentTargetIsSkippedDuringClaimRoundRobin() {
    InMemoryHarnessStore store = newStore();
    ThreadSeed seed = seedThread(store);
    UUID threadId = seed.threadId();

    // 登记 AGENT Work
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.AGENT, threadId));
          return null;
        });

    List<ClaimedWork> threadClaims = new CopyOnWriteArrayList<>();
    // Dispatcher 只注册 THREAD 处理器，未注册 AGENT
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            store,
            config(1),
            singleThread(),
            singleThread(),
            new RecordingScheduler(),
            Map.of(WorkTargetType.THREAD, threadClaims::add));
    ownedDispatchers.add(dispatcher);
    dispatcher.start();
    dispatcher.wake();

    // seedThread 已内置一条 available 的 THREAD Work，先断言 THREAD 被正常 claim
    awaitTrue(() -> !threadClaims.isEmpty());
    assertEquals(new WorkTarget(WorkTargetType.THREAD, threadId), threadClaims.get(0).target());

    // 再次 wake 多个周期，断言 AGENT Work 从未被 claim，其 leaseToken 仍为 null
    dispatcher.wake();
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    store.transaction(
        tx -> {
          Work agentWork =
              tx.work().find(new WorkTarget(WorkTargetType.AGENT, threadId)).orElseThrow();
          assertNull(agentWork.leaseToken());
          assertNull(agentWork.leaseUntil());
          assertEquals(1L, agentWork.wakeVersion());
          return null;
        });
  }
}
