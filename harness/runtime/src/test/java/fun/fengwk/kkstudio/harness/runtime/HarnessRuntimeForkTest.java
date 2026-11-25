package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
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

    // 创建请求重放返回当前 Thread，不重置已接受命令的序号。
    assertEquals(
        accepted.thread(),
        runtime.createIdleBranch(
            baseline.threadId(), baseline.turnEndEntryId(), branch.id(), "branch"));
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

    // 重放不能再次复制历史或保留资源。
    assertEquals(
        fork,
        runtime.createIdleFork(
            baseline.threadId(),
            baseline.turnEndEntryId(),
            fork.sessionId(),
            fork.id(),
            "fork",
            (sessionId, entries) -> {
              throw new AssertionError("replay must not retain resources twice");
            }));
  }

  @Test
  void branchCutValidationRequiresATurnEndOnTheSourcePath() {
    var baseline = HarnessRuntimeTestSupport.seedContinuationChain(store, true);
    runtime.validateBranchCut(baseline.threadId(), baseline.turnEndEntryId());
    var error =
        assertThrows(
            DomainException.class,
            () -> runtime.validateBranchCut(baseline.threadId(), baseline.rootEntryId()));
    assertEquals(HarnessConflictReason.INVALID_TURN_END.name(), error.reason());
  }

  @Test
  void idleBranchDistinguishesReusedIdFromDuplicateName() {
    var baseline = HarnessRuntimeTestSupport.seedContinuationChain(store, true);
    var branch =
        runtime.createIdleBranch(
            baseline.threadId(), baseline.turnEndEntryId(), TestIds.id(900), "branch");
    var reusedId =
        assertThrows(
            DomainException.class,
            () ->
                runtime.createIdleBranch(
                    baseline.threadId(), baseline.turnEndEntryId(), branch.id(), "different"));
    assertEquals(HarnessConflictReason.THREAD_ID_REUSED.name(), reusedId.reason());
    var duplicateName =
        assertThrows(
            DomainException.class,
            () ->
                runtime.createIdleBranch(
                    baseline.threadId(), baseline.turnEndEntryId(), TestIds.id(901), "branch"));
    assertEquals(HarnessConflictReason.THREAD_NAME_CONFLICT.name(), duplicateName.reason());
  }
}
