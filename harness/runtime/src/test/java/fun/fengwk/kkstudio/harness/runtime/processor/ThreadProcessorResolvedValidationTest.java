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

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验：任何不一致都是 Resolver 契约 / 编程错误，抛 ISE 且零 Entry / Command /
 * Thread / Invocation mutation，绝不转 typed rejection / reschedule。
 */
class ThreadProcessorResolvedValidationTest extends ThreadProcessorTestBase {

  @Test
  void environmentBoundToolBindsDirectEnvironmentIdOnBranchWithoutDirectory() {
    // branch 不再持有目录：environment-required tool 的 environmentId 与 branch 事实不构成冲突，正常落库。
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Resolved(requestWithEnvironmentTool(branchSettings()), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));
    // 基线 ROOT + 本轮 TURN_START + USER = 3：environment-required tool 不再引入任何目录事实。
    assertEquals(3, path(fixture.store, baseline.threadId()).entries().size());
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

  /** candidate 默认 branch 事实下（settings = branchSettings()）请求与事实不一致。 */
  private void assertMismatchRollsBack(BranchSettings mismatchedSettings) {
    assertMismatchRollsBack(requestFor(mismatchedSettings));
  }

  private void assertMismatchRollsBack(ModelRequestSpec mismatchedSpec) {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(mismatchedSpec, 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));

    // 零 Entry mutation：ROOT 之外没有任何 candidate Entry 落库。
    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    // 零 Command mutation：命令仍 QUEUED，未被 consume。
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // 零 Thread mutation：seedCommand 的 reserveCommandSequences 已 +1，失败的校验没有再次改变 version / head。
    assertEquals(1L, thread(fixture.store, baseline.threadId()).version());
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

  private static ModelRequestSpec requestWithEnvironmentTool(BranchSettings settings) {
    ToolBinding environmentTool =
        new ToolBinding(
            new AgentToolDefinition(
                new ToolDescriptor(
                    "fs",
                    "filesystem",
                    "fs",
                    new InputSchema("arguments", Map.of(), Set.of(), false),
                    ToolSideEffect.READ_ONLY,
                    Duration.ofSeconds(30)),
                ToolVisibility.SELECTABLE),
            new ContributorBinding("base", "fs", List.of()),
            EnvironmentSupport.REQUIRED,
            EnvironmentId.parse("11111111-1111-1111-1111-111111111111"),
            "dev");
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor(
            settings.model().providerName(),
            settings.model().modelName(),
            settings.model().modelName(),
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            new ModelPricing(
                "USD",
                "standard",
                "standard",
                BigDecimal.ONE,
                "1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        new ModelVariant(settings.model().variant()),
        1024,
        "Test system instruction.",
        List.of(environmentTool),
        List.of(),
        ProviderCacheControl.none());
  }
}
