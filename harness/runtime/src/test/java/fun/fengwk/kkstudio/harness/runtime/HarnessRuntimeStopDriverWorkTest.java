package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.DEFAULT_COMPACTION_PROVIDER;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedOpenTurn;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessTransaction;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver.FakeLockedDriverState;
import fun.fengwk.kkstudio.harness.spi.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 验证 Stop 控制面遵循 LockedDriverState.workTarget 契约：显式声明的 Work 目标在 Stop 期间被原子锁定并删除；未声明时无关的 MODEL Work
 * 行不受影响。
 */
class HarnessRuntimeStopDriverWorkTest {

  private InMemoryHarnessStore store;
  private FakeAgentDriver driver;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    driver = new FakeAgentDriver("fake");
    Clock clock = Clock.fixed(T5, ZoneOffset.UTC);
    HarnessRuntimeTestSupport.useClock(store, clock);
    runtime =
        new HarnessRuntime(store, new DriverRegistry(List.of(driver)), DEFAULT_COMPACTION_PROVIDER);
  }

  @Test
  void declaredDriverWorkTargetIsDeletedOnStop() {
    // 构造打开的 TURN_START 回合 baseline 并追加 USER 消息
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    UUID userEntryId = id(11L);
    store.transaction(
        tx -> {
          tx.entries()
              .insert(
                  userMessageEntry(
                      userEntryId, baseline.sessionId(), baseline.turnStartEntryId(), T1));
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads().update(thread.advanceHead(userEntryId, T1));
          return null;
        });

    UUID modelWorkId = id(100L);
    WorkTarget target = new WorkTarget(WorkTargetType.MODEL, modelWorkId);

    // 预先向 Store 写入该 MODEL Work 行
    store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          ((InMemoryHarnessTransaction) tx)
              .insertDriverRow(modelWorkId, baseline.threadId(), new Object());
          tx.work().request(target);
          return null;
        });
    assertTrue(store.transaction(tx -> tx.work().find(target)).isPresent());

    // 配置 FakeAgentDriver 显式声明该 workTarget
    driver.withState(
        new FakeLockedDriverState(DriverActivity.ACTIVE, userEntryId, null, null, target));

    // 执行 Stop
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), id(1L), 1));
    assertStopped(result);

    // 意图验证：声明的 workTarget 对应的 Work 行在 Stop 事务的 Work 阶段被精确删除
    assertFalse(store.transaction(tx -> tx.work().find(target)).isPresent());
  }

  @Test
  void undeclaredWorkTargetDoesNotDeleteUnrelatedModelWorkOnStop() {
    // 构造打开的 TURN_START 回合 baseline 并追加 USER 消息
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    UUID userEntryId = id(12L);
    store.transaction(
        tx -> {
          tx.entries()
              .insert(
                  userMessageEntry(
                      userEntryId, baseline.sessionId(), baseline.turnStartEntryId(), T1));
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads().update(thread.advanceHead(userEntryId, T1));
          return null;
        });

    UUID unrelatedModelWorkId = id(200L);
    WorkTarget unrelatedTarget = new WorkTarget(WorkTargetType.MODEL, unrelatedModelWorkId);

    // 预先向 Store 写入一条无关的 MODEL Work 行
    store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          ((InMemoryHarnessTransaction) tx)
              .insertDriverRow(unrelatedModelWorkId, baseline.threadId(), new Object());
          tx.work().request(unrelatedTarget);
          return null;
        });
    assertTrue(store.transaction(tx -> tx.work().find(unrelatedTarget)).isPresent());

    // 配置 FakeAgentDriver 不声明 workTarget（为 null）
    driver.withState(
        new FakeLockedDriverState(
            DriverActivity.ACTIVE, userEntryId, null, null, (WorkTarget) null));

    // 执行 Stop
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), id(1L), 1));
    assertStopped(result);

    // 意图验证：内核未收到 Driver 的 workTarget 声明时，不臆测删除任何 MODEL Work 行，无关的 MODEL Work 保留
    assertTrue(store.transaction(tx -> tx.work().find(unrelatedTarget)).isPresent());
  }
}
