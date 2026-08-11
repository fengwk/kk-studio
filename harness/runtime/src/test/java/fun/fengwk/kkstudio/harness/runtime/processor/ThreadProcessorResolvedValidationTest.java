package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestFor;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.util.List;
import java.util.UUID;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验：任何不一致都是 Resolver 契约 / 编程错误，抛 ISE 且零 Entry / Command /
 * Thread / Invocation mutation，绝不转 typed rejection / reschedule。
 */
class ThreadProcessorResolvedValidationTest extends ThreadProcessorTestBase {

  @Test
  void routeMismatchIsContractErrorWithZeroMutation() {
    assertMismatchRollsBack(branchSettings().withEnvironmentName(new EnvironmentName("env-2")));
  }

  @Test
  void yoloMismatchIsContractErrorWithZeroMutation() {
    // settings 一致，但请求 yolo=true 与 candidate finalYoloEnabled=false 不符。
    assertMismatchRollsBack(branchSettings(), true);
  }

  @Test
  void modelMismatchIsContractErrorWithZeroMutation() {
    assertMismatchRollsBack(
        branchSettings().withModel(new ModelSelection("other-provider", "other-model", "v1")));
  }

  @Test
  void variantMismatchIsContractErrorWithZeroMutation() {
    assertMismatchRollsBack(
        branchSettings().withModel(new ModelSelection("provider", "model", "v9")));
  }

  @Test
  void toolBindingsMismatchIsContractErrorWithZeroMutation() {
    assertMismatchRollsBack(branchSettings().withActiveTools(List.of("bash")));
  }

  /** candidate 默认 branch 事实下（settings = branchSettings()，yolo = false）请求与事实不一致。 */
  private void assertMismatchRollsBack(BranchSettings mismatchedSettings) {
    assertMismatchRollsBack(mismatchedSettings, false);
  }

  private void assertMismatchRollsBack(BranchSettings mismatchedSettings, boolean requestedYolo) {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Resolved(requestFor(mismatchedSettings, requestedYolo)));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));

    // 零 Entry mutation：ROOT 之外没有任何 candidate Entry 落库。
    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    // 零 Command mutation：命令仍 QUEUED，未被 consume。
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // 零 Thread mutation：seedCommand 的 reserveCommandSequences 已 +1，失败的校验没有再次改变 revision / head。
    assertEquals(1L, thread(fixture.store, baseline.threadId()).revision());
    assertEquals(baseline.rootEntryId(), thread(fixture.store, baseline.threadId()).headEntryId());
    // 零 Invocation mutation：candidate TURN_START 下不存在任何 ModelInvocation。
    UUID candidateTurnStartIdUuid = fixture.resolver.lastPath.entries().get(1).id();
    assertTrue(
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), candidateTurnStartIdUuid))
            .isEmpty());
    // 未被 reschedule / complete / 转 rejection：Work 行仍带 claim lease。
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNotNull(threadWork.leaseToken());
    assertEquals(NOW.plusSeconds(60), threadWork.leaseUntil());
  }
}
