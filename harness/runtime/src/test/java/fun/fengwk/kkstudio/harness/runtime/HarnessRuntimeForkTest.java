package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

class HarnessRuntimeForkTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void idleBranchCanAcceptCommandsOnItsExistingThread() {
    HarnessRuntimeTestSupport.ContinuationBaseline baseline =
        HarnessRuntimeTestSupport.seedContinuationChain(store, true);
    var branch =
        runtime.createIdleBranch(
            baseline.threadId(), baseline.turnEndEntryId(), TestIds.id(900), "branch");

    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(branch.id()),
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertEquals(branch.id(), accepted.thread().id());
    assertEquals(baseline.sessionId(), accepted.session().id());
    EntryPath path =
        store.transaction(tx -> tx.entries().loadPath(accepted.thread().headEntryId()));
    assertEquals(baseline.turnEndEntryId(), path.head().id());
  }

  @Test
  void idleSessionForkCopiesHistoryWithoutAcceptingCommands() {
    HarnessRuntimeTestSupport.ContinuationBaseline baseline =
        HarnessRuntimeTestSupport.seedContinuationChain(store, true);
    var fork =
        runtime.createIdleFork(
            baseline.threadId(),
            baseline.turnEndEntryId(),
            TestIds.id(901),
            TestIds.id(902),
            "fork",
            (sessionId, entries) -> {});

    assertEquals(TestIds.id(901), fork.sessionId());
    assertEquals(1L, fork.nextCommandSequence());
    EntryPath path = store.transaction(tx -> tx.entries().loadPath(fork.headEntryId()));
    assertEquals(5, path.entries().size());
    assertEquals(TestIds.id(901), path.head().sessionId());
  }
}
