package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.UUID;

/** 真实 PostgreSQL 验证 idle branch/fork 的历史持久化及创建重放。 */
class PostgresqlForkContractTest {

  @Test
  void idleForkCopiesHistoryAndReplaysIdempotently() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    HarnessRuntime runtime = new HarnessRuntime(store, new DriverRegistry(), () -> null);
    Baseline baseline = seedThreadBaseline(store);
    UUID cut = stopAtVisibleTurn(runtime, baseline);
    var sourcePath = store.transaction(tx -> tx.entries().loadPath(cut));
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();

    var fork =
        runtime.createIdleFork(
            baseline.threadId(),
            cut,
            sessionId,
            threadId,
            "fork",
            (retainedSessionId, copiedEntries) -> {
              assertEquals(sessionId, retainedSessionId);
              assertEquals(sourcePath.entries().size() - 1, copiedEntries.size());
            });
    assertEquals(sessionId, fork.sessionId());
    assertEquals(threadId, fork.id());
    assertNull(fork.parentThreadId());

    var forkPath = store.transaction(tx -> tx.entries().loadPath(fork.headEntryId()));
    assertEquals(sourcePath.entries().size(), forkPath.entries().size());
    assertEquals(sessionId, forkPath.root().sessionId());
    var sourceSettings =
        store.transaction(tx -> tx.entries().loadBranchSettings(baseline.rootEntryId()));
    var forkSettings =
        store.transaction(tx -> tx.entries().loadBranchSettings(forkPath.root().id()));
    assertEquals(sourceSettings, forkSettings);
    for (int index = 1; index < forkPath.entries().size(); index++) {
      assertNotEquals(sourcePath.entries().get(index).id(), forkPath.entries().get(index).id());
      assertEquals(sessionId, forkPath.entries().get(index).sessionId());
    }

    var replay =
        runtime.createIdleFork(
            baseline.threadId(),
            cut,
            sessionId,
            threadId,
            "fork",
            (retainedSessionId, copiedEntries) -> {
              throw new AssertionError("replay must not retain copied resources");
            });
    assertEquals(fork, replay);
    assertTrue(store.transaction(tx -> loadCommandsByThread(tx, threadId)).isEmpty());
  }

  @Test
  void idleBranchSharesCutWithoutCopyingHistory() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    HarnessRuntime runtime = new HarnessRuntime(store, new DriverRegistry(), () -> null);
    Baseline baseline = seedThreadBaseline(store);
    UUID cut = stopAtVisibleTurn(runtime, baseline);
    UUID threadId = UUID.randomUUID();

    var branch = runtime.createIdleBranch(baseline.threadId(), cut, threadId, "branch");
    assertEquals(baseline.sessionId(), branch.sessionId());
    assertEquals(cut, branch.headEntryId());
    assertNull(branch.parentThreadId());
    assertNotEquals(baseline.threadId(), branch.id());
    var sourcePath = store.transaction(tx -> tx.entries().loadPath(cut));
    var branchPath = store.transaction(tx -> tx.entries().loadPath(branch.headEntryId()));
    assertEquals(
        sourcePath.entries().stream().map(entry -> entry.id()).toList(),
        branchPath.entries().stream().map(entry -> entry.id()).toList());
    assertEquals(branch, runtime.createIdleBranch(baseline.threadId(), cut, threadId, "branch"));
  }

  private static UUID stopAtVisibleTurn(HarnessRuntime runtime, Baseline baseline) {
    return runtime
        .stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), 0))
        .thread()
        .headEntryId();
  }
}
