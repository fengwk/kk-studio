package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/** 当前创建 API 的 Session 与 Thread 名称规则。 */
class HarnessNamesTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void rootSessionNameComesFromInitialUserText() {
    var accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(
                    TestIds.id(101), TestIds.id(102), settings(), false),
                List.of(userMessageCommand(TestIds.id(1), "  first  message\ncontinued "))),
            AcceptancePreflight.IDENTITY);

    assertEquals("first message continued", accepted.session().name());
    assertEquals("main", accepted.thread().name());
  }

  @Test
  void idleBranchUsesCallerProvidedNormalizedName() {
    var baseline = HarnessRuntimeTestSupport.seedContinuationChain(store, true);
    var branch =
        runtime.createIdleBranch(
            baseline.threadId(), baseline.turnEndEntryId(), TestIds.id(401), "  发布  分支 ");

    assertEquals("发布 分支", branch.name());
    assertEquals(
        "session-" + baseline.sessionId(),
        store.transaction(tx -> tx.sessions().find(baseline.sessionId()).orElseThrow().name()));
  }
}
