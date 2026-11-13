package fun.fengwk.kkstudio.harness.builtin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 模型组：冷却候选选择、fallback 冻结与成员推进、组预算和租约恢复的专用语义。 */
class ModelProcessorGroupTest extends ModelProcessorTestSupport {
  /** 两成员模型组冻结请求：成员逻辑名用于断言真实 gateway 身份，而非仅顶层组名。 */
  private static ModelRequestSpec groupSpec(Set<Integer> fallbackStatusCodes, int cooldownSeconds) {
    List<ModelGroupMemberExecution> members = List.of(groupMember("model"), groupMember("model2"));
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new ModelDescriptor("group", "group", "group", Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("v1", null, PromptCacheRetention.NONE, null),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        new ModelGroupExecution("group", 7L, members, fallbackStatusCodes, cooldownSeconds));
  }

  private static ModelGroupMemberExecution groupMember(String name) {
    return new ModelGroupMemberExecution(
        ProviderType.OPENAI,
        new ModelDescriptor("provider", name, name, Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("v1", null, PromptCacheRetention.NONE, null));
  }

  private static void freezeGroupMember(Fixture fixture, String member, Instant until) {
    fixture.store.transaction(
        tx -> {
          fixture.gateway.freezeGroupMember(tx, "group", 7L, member, "v1", until);
          return null;
        });
  }

  /** 在 fixture 当前时钟下该成员是否仍处于组冷却。 */
  private static boolean cooling(Fixture fixture, String member) {
    return fixture.store.transaction(
        tx -> fixture.gateway.isGroupMemberCooling(tx, "group", 7L, member, "v1"));
  }

  private static ModelGroupProgress groupProgress(ModelInvocation invocation) {
    assertNotNull(invocation.groupProgress());
    return invocation.groupProgress();
  }

  /** 组首成员冷却时在运行前推进 durable 游标，真实启动第二成员。 */
  @Test
  void groupSkipsCoolingFirstMemberAndStartsSecond() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(), 30));
    freezeGroupMember(fixture, "model", NOW.plusSeconds(30));
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals("model2", fixture.gateway.executions.getFirst().request().model().modelName());
    ModelInvocation running = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, running.status());
    assertEquals(1, running.attempt());
    assertEquals(1, groupProgress(running).memberIndex());
  }

  /** 全成员冷却是无可用候选的本地专用失败：不准入 gateway，也不伪装成普通上游重试。 */
  @Test
  void groupAllMembersCoolingFailsLocallyWithoutGatewayOrRetry() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(), 30));
    freezeGroupMember(fixture, "model", NOW.plusSeconds(30));
    freezeGroupMember(fixture, "model2", NOW.plusSeconds(30));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(0, fixture.gateway.admitCalls);
    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.NO_AVAILABLE_MODEL, failed.error().kind());
    assertNull(failed.error().httpStatus());
    assertFalse(failed.error().retryCandidate());
    assertTrue(failed.error().message().contains("no available model in group"));
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /** 命中 fallback 码立即冻结并推进，不消耗普通重试预算；失败审计带实际成员与请求时间，下一成员立即可执行。 */
  @Test
  void groupFallbackStatusFreezesAndAdvancesToNextMemberWithoutOrdinaryRetry() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(429), 30));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "limited", 429));

    ModelInvocation advanced = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, advanced.status());
    assertEquals(1, advanced.attempt());
    assertEquals(1, groupProgress(advanced).memberIndex());
    assertEquals(0, groupProgress(advanced).memberAttempts());
    assertEquals(ModelSelection.model("model", "v1"), advanced.failedAttempts().getFirst().model());
    assertEquals(NOW, advanced.failedAttempts().getFirst().requestedAt());
    assertTrue(cooling(fixture, "model"));
    assertFalse(cooling(fixture, "model2"));

    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    assertEquals("model2", fixture.gateway.executions.get(1).request().model().modelName());
  }

  /** 非 fallback 的可重试错误先消耗当前成员预算；预算耗尽后才冻结并推进，失败审计逐次指向真实成员。 */
  @Test
  void groupNonFallbackFailureRetriesCurrentMemberThenFreezesAndAdvances() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            groupSpec(Set.of(429), 30));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "server", 500));

    ModelInvocation retried = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(0, groupProgress(retried).memberIndex());
    assertEquals(1, groupProgress(retried).memberAttempts());
    assertFalse(cooling(fixture, "model"));

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "server2", 500));

    ModelInvocation advanced = model(fixture.store, fixture.invocationId);
    assertEquals(1, groupProgress(advanced).memberIndex());
    assertEquals(2, advanced.failedAttempts().size());
    assertEquals(ModelSelection.model("model", "v1"), advanced.failedAttempts().getLast().model());
    assertTrue(cooling(fixture, "model"));
  }

  /** cooldown=0 只推进不写跨调用冷却；所有成员失败后保留最后一次真实上游错误。 */
  @Test
  void groupZeroCooldownAdvancesWithoutFreezingAndKeepsLastError() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(429), 0));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "limited", 429));

    ModelInvocation advanced = model(fixture.store, fixture.invocationId);
    assertEquals(1, groupProgress(advanced).memberIndex());
    assertFalse(cooling(fixture, "model"));

    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "denied", 401));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.AUTHENTICATION, failed.error().kind());
    assertEquals(401, failed.error().httpStatus());
    assertEquals(2, failed.failedAttempts().size());
    assertEquals(ModelSelection.model("model2", "v1"), failed.failedAttempts().getLast().model());
  }

  /** 上下文溢出在组内仍走专用终止：不冻结、不推进成员、不追加降级审计，原样交给压缩路径。 */
  @Test
  void groupOverflowFailureKeepsDedicatedTerminalSemantics() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(), 30));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.CONTEXT_LIMIT, "context too long"));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.CONTEXT_LIMIT, failed.error().kind());
    assertTrue(failed.failedAttempts().isEmpty());
    assertEquals(0, groupProgress(failed).memberIndex());
    assertFalse(cooling(fixture, "model"));
  }

  /** 租约丢失恢复只消耗当前成员预算：允许时留在原成员，耗尽时原成员失败，绝不冻结或切换成员。 */
  @Test
  void groupLeaseRecoveryConsumesCurrentMemberBudgetWithoutFreezeOrFallback() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            groupSpec(Set.of(), 30));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(recovered));

    ModelInvocation retried = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(0, groupProgress(retried).memberIndex());
    assertEquals(1, groupProgress(retried).memberAttempts());
    assertEquals(ModelSelection.model("model", "v1"), retried.failedAttempts().getLast().model());
    assertFalse(cooling(fixture, "model"));

    // 同一成员第二次租约丢失：成员预算耗尽，直接 FAILED，仍不冻结、不切到 model2。
    fixture.clock.advance(Duration.ofSeconds(5));
    transition(
        fixture.store, fixture.invocationId, model -> model.markRunning(fixture.clock.instant()));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork exhausted = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(exhausted));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(0, groupProgress(failed).memberIndex());
    assertEquals(ProviderErrorKind.TRANSIENT, failed.error().kind());
    assertEquals(2, failed.failedAttempts().size());
    assertFalse(cooling(fixture, "model"));
    assertEquals(0, fixture.gateway.admitCalls);
  }
}
