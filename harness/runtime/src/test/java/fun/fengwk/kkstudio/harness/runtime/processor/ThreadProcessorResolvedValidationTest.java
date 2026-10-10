package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestFor;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验：任何不一致都是 Resolver 契约 / 编程错误，此刻零 durable mutation，随后按确定性
 * 失败落 durable AssistantError + FAILED TURN_END 并结算 Join——绝不创建 ModelInvocation，也绝不无限 reschedule。
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
  void modelMismatchBecomesDurableFailure() {
    assertMismatchBecomesDurableFailure(
        branchSettings().withModel(new ModelSelection("other-provider", "other-model", "v1")));
  }

  @Test
  void variantMismatchBecomesDurableFailure() {
    assertMismatchBecomesDurableFailure(
        branchSettings().withModel(new ModelSelection("provider", "model", "v9")));
  }

  /** candidate 默认 branch 事实下（settings = branchSettings()）请求与事实不一致。 */
  private void assertMismatchBecomesDurableFailure(BranchSettings mismatchedSettings) {
    assertMismatchBecomesDurableFailure(requestFor(mismatchedSettings));
  }

  private void assertMismatchBecomesDurableFailure(ModelRequestSpec mismatchedSpec) {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(mismatchedSpec, 100_000, 16_384));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // 契约失败没有产生 resolved request：durable 只落 AssistantError + FAILED TURN_END。
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(1).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    AssistantErrorPayload error = (AssistantErrorPayload) path.entries().get(3).payload();
    assertEquals("TURN_RESOLVE_FAILED", error.error().code());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(path.entries().get(1).id(), end.turnStartEntryId());
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // 零 Invocation mutation：候选 TURN_START 下不存在任何 ModelInvocation。
    assertTrue(
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(1).id()))
            .isEmpty());
    // 无 deferred demand：不保留 THREAD Work（确定性失败不自我重排）。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
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
            true),
        new ModelVariant(settings.model().variant()),
        1024,
        "Test system instruction.",
        List.of(environmentTool),
        List.of(),
        ProviderCacheControl.none());
  }
}
