package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/** 初始根创建与既有 Thread 接纳入口。 */
class HarnessRuntimeAcceptInitialTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void newRootSessionAcceptsItsInitialCommandsAtomically() {
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(
                    TestIds.id(101), TestIds.id(102), settings(), true),
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertEquals(TestIds.id(101), accepted.session().id());
    assertEquals(TestIds.id(102), accepted.thread().id());
    assertEquals(1, accepted.acceptedCommands().size());
    assertEquals(2L, accepted.thread().nextCommandSequence());
    long version =
        store.transaction(tx -> tx.threads().find(TestIds.id(102)).orElseThrow().version());
    assertEquals(1L, version);
  }

  @Test
  void acceptsCommandsOnAnExistingThread() {
    var baseline = HarnessRuntimeTestSupport.seedContinuationChain(store, true);
    var branch =
        runtime.createIdleBranch(
            baseline.threadId(), baseline.turnEndEntryId(), TestIds.id(203), "branch");

    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(branch.id()),
                List.of(userMessageCommand(TestIds.id(2), "branch work"))),
            AcceptancePreflight.IDENTITY);

    assertEquals(branch.id(), accepted.thread().id());
    assertEquals(baseline.sessionId(), accepted.session().id());
    assertEquals(1, accepted.acceptedCommands().size());
    assertEquals(2L, accepted.thread().nextCommandSequence());
  }
}
