package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelPricingCondition;
import fun.fengwk.kkstudio.harness.model.ModelRequestBodyPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.RequestBodyPricingSnapshot;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 生产 {@link PlatformModelGateway} 的准入与阻塞执行契约：admit 只做解析与本地名额判定（确定性解析失败为 Rejected、名额耗尽为 Busy、其它
 * 解析失败原样传播），run 在调用线程上把 Provider 回调收敛为增量与唯一终态，名额在 run 返回或 close 时恰好释放一次。
 */
class PlatformModelGatewayTest {

  private static final Duration BUSY_DELAY = Duration.ofSeconds(7);
  private static final ModelCallTimeoutPolicy TIMEOUT_POLICY =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(45), Duration.ofSeconds(3));
  private static final ProviderRequest PROVIDER_REQUEST = providerRequest();
  private static final UUID CONNECTION_GENERATION_ID = new UUID(0L, 43L);
  private static final ModelGateway.Execution EXECUTION =
      new ModelGateway.Execution(
          new UUID(0L, 42L), 1, ProviderType.OPENAI, CONNECTION_GENERATION_ID, PROVIDER_REQUEST);

  /** admit 只解析与占名额，不打开 Provider；run 把增量同步转交 sink 并以成功终态返回（保留 native replay 状态）。 */
  @Test
  void admittedRunBridgesDeltasAndCompletion() {
    ProviderCompletion completion = new ProviderCompletion(response(), replayState());
    Resolution resolution =
        new Resolution(
            scripted(
                (request, handler) -> {
                  handler.onEvent(new ProviderStreamEvent.TextDelta("he"));
                  handler.onEvent(new ProviderStreamEvent.TextDelta("llo"));
                  handler.onComplete(completion);
                }));
    PlatformModelGateway gateway = gateway(resolution, new ConcurrencyAdmission(1));

    ModelGateway.Admitted admitted =
        assertInstanceOf(ModelGateway.Admitted.class, gateway.admit(EXECUTION));
    assertEquals(1, resolution.resolveCount.get());
    assertEquals(0, resolution.openCount.get(), "admit must not open the provider");

    List<ProviderStreamEvent> deltas = new ArrayList<>();
    ModelGateway.Succeeded succeeded =
        assertInstanceOf(ModelGateway.Succeeded.class, admitted.run(deltas::add));

    assertSame(completion, succeeded.completion());
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("he"), new ProviderStreamEvent.TextDelta("llo")),
        deltas);
    assertEquals(TIMEOUT_POLICY, resolution.openedPolicy.get());
    // 名额随 run 返回释放：下一次 admit 立即成功。
    assertInstanceOf(ModelGateway.Admitted.class, gateway.admit(EXECUTION)).close();
  }

  /** Provider 收到的是解析得到的 effective request，而不是 Runtime 原始请求。 */
  @Test
  void streamsTheEffectiveRequestFromResolution() {
    ProviderRequest effective = providerRequest("effective-model");
    AtomicReference<ProviderRequest> seen = new AtomicReference<>();
    Resolution resolution =
        new Resolution(
            effective,
            scripted(
                (request, handler) -> {
                  seen.set(request);
                  handler.onComplete(new ProviderCompletion(response()));
                }));

    run(gateway(resolution, new ConcurrencyAdmission(1)));

    assertSame(effective, seen.get());
  }

  /** 名额耗尽：Busy 携带现读的重试延迟，且不打开 Provider；close 释放未运行的名额。 */
  @Test
  void exhaustedCapacityIsBusyAndCloseReleasesTheSlot() {
    Resolution resolution = new Resolution(scripted((request, handler) -> {}));
    PlatformModelGateway gateway = gateway(resolution, new ConcurrencyAdmission(1));

    ModelGateway.Admitted holder =
        assertInstanceOf(ModelGateway.Admitted.class, gateway.admit(EXECUTION));
    ModelGateway.Busy busy = assertInstanceOf(ModelGateway.Busy.class, gateway.admit(EXECUTION));
    assertEquals(BUSY_DELAY, busy.retryAfter());

    holder.close();
    holder.close();
    assertInstanceOf(ModelGateway.Admitted.class, gateway.admit(EXECUTION)).close();
    assertEquals(0, resolution.openCount.get());
  }

  /** 确定性解析失败（IllegalArgumentException）是 INVALID_REQUEST 的 Rejected；其它解析失败原样传播供重新调度。 */
  @Test
  void classifiesResolutionFailures() {
    Resolution invalid = new Resolution(scripted((request, handler) -> {}));
    invalid.resolveFailure = new IllegalArgumentException("unknown provider");
    ModelGateway.Rejected rejected =
        assertInstanceOf(
            ModelGateway.Rejected.class,
            gateway(invalid, new ConcurrencyAdmission(1)).admit(EXECUTION));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, rejected.error().kind());

    Resolution transientFailure = new Resolution(scripted((request, handler) -> {}));
    IllegalStateException failure = new IllegalStateException("database down");
    transientFailure.resolveFailure = failure;
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> gateway(transientFailure, new ConcurrencyAdmission(1)).admit(EXECUTION)));
  }

  /** 打开 Provider 失败（adapter 装配错误）是确定性 INVALID_REQUEST 失败，名额照常释放。 */
  @Test
  void providerSetupFailureIsInvalidRequest() {
    Resolution resolution =
        new Resolution(
            policy -> {
              throw new IllegalStateException("bad adapter config");
            });
    PlatformModelGateway gateway = gateway(resolution, new ConcurrencyAdmission(1));

    ModelGateway.Failed failed = assertInstanceOf(ModelGateway.Failed.class, run(gateway));

    assertEquals(ProviderErrorKind.INVALID_REQUEST, failed.error().kind());
    assertInstanceOf(ModelGateway.Admitted.class, gateway.admit(EXECUTION)).close();
  }

  /** onError 交付的分类异常与 Provider 直接抛出的分类异常都以同一 kind 的 Failed 返回。 */
  @Test
  void classifiedProviderFailuresKeepTheirKind() {
    ModelGateway.Failed viaCallback =
        assertInstanceOf(
            ModelGateway.Failed.class,
            run(
                gateway(
                    new Resolution(
                        scripted(
                            (request, handler) ->
                                handler.onError(
                                    new ProviderException(
                                        ProviderErrorKind.TRANSIENT, "rate limited")))),
                    new ConcurrencyAdmission(1))));
    assertEquals(ProviderErrorKind.TRANSIENT, viaCallback.error().kind());

    ModelGateway.Failed thrown =
        assertInstanceOf(
            ModelGateway.Failed.class,
            run(
                gateway(
                    new Resolution(
                        scripted(
                            (request, handler) -> {
                              throw new ProviderException(
                                  ProviderErrorKind.INVALID_REQUEST, "bad request");
                            })),
                    new ConcurrencyAdmission(1))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, thrown.error().kind());
  }

  /** 未分类异常（含 sink 抛出）无法确认结果：Unknown，且只记录异常类型，不泄露可能含请求细节或凭据的消息。 */
  @Test
  void unclassifiedFailuresAreUnknownWithoutLeakingTheMessage() {
    ModelGateway.Unknown providerCrash =
        assertInstanceOf(
            ModelGateway.Unknown.class,
            run(
                gateway(
                    new Resolution(
                        scripted(
                            (request, handler) -> {
                              throw new IllegalStateException("token=sk-secret");
                            })),
                    new ConcurrencyAdmission(1))));
    assertFalse(providerCrash.error().message().contains("sk-secret"));
    assertTrue(providerCrash.error().message().contains("IllegalStateException"));

    PlatformModelGateway sinkFailing =
        gateway(
            new Resolution(
                scripted(
                    (request, handler) -> {
                      handler.onEvent(new ProviderStreamEvent.TextDelta("x"));
                      handler.onComplete(new ProviderCompletion(response()));
                    })),
            new ConcurrencyAdmission(1));
    ModelGateway.Admitted admitted =
        assertInstanceOf(ModelGateway.Admitted.class, sinkFailing.admit(EXECUTION));
    assertInstanceOf(
        ModelGateway.Unknown.class,
        admitted.run(
            event -> {
              throw new IllegalStateException("sink broke with sk-secret");
            }));
  }

  /** Provider 返回却没有交付终态：结果无法确认，返回 Unknown；中断导致的无终态返回为 CANCELLED。 */
  @Test
  void missingTerminalIsUnknownUnlessInterrupted() {
    assertInstanceOf(
        ModelGateway.Unknown.class,
        run(
            gateway(
                new Resolution(scripted((request, handler) -> {})), new ConcurrencyAdmission(1))));

    ModelGateway.Outcome cancelled =
        run(
            gateway(
                new Resolution(scripted((request, handler) -> Thread.currentThread().interrupt())),
                new ConcurrencyAdmission(1)));
    assertTrue(Thread.interrupted());
    ModelGateway.Failed failed = assertInstanceOf(ModelGateway.Failed.class, cancelled);
    assertEquals(ProviderErrorKind.CANCELLED, failed.error().kind());
  }

  /** 终态之后的增量与重复终态被忽略；null 的增量 / completion / error 是 adapter 缺陷，确定性 INVALID_REQUEST 失败。 */
  @Test
  void firstTerminalWinsAndNullPayloadsAreInvalid() {
    AtomicInteger deltas = new AtomicInteger();
    PlatformModelGateway lateCallbacks =
        gateway(
            new Resolution(
                scripted(
                    (request, handler) -> {
                      handler.onComplete(new ProviderCompletion(response()));
                      handler.onEvent(new ProviderStreamEvent.TextDelta("late"));
                      handler.onError(new ProviderException(ProviderErrorKind.TRANSIENT, "late"));
                    })),
            new ConcurrencyAdmission(1));
    ModelGateway.Admitted admitted =
        assertInstanceOf(ModelGateway.Admitted.class, lateCallbacks.admit(EXECUTION));
    assertInstanceOf(ModelGateway.Succeeded.class, admitted.run(event -> deltas.incrementAndGet()));
    assertEquals(0, deltas.get());

    for (BiConsumer<ProviderRequest, ProviderStreamHandler> nullPayload :
        List.<BiConsumer<ProviderRequest, ProviderStreamHandler>>of(
            (request, handler) -> handler.onEvent(null),
            (request, handler) -> handler.onComplete(null),
            (request, handler) -> handler.onError(null))) {
      ModelGateway.Failed failed =
          assertInstanceOf(
              ModelGateway.Failed.class,
              run(gateway(new Resolution(scripted(nullPayload)), new ConcurrencyAdmission(1))));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, failed.error().kind());
    }
  }

  /** 最终编码 body 观察只发生在真实 stream 路径；计划逐 attempt 冻结，不把上一次快照带给下一次执行。 */
  @Test
  void observesActualBodyAndKeepsPlansPerAttempt() {
    ModelPricingCondition.RequestBody condition =
        ModelPricingCondition.RequestBody.stringValue("/speed", "fast");
    ModelPricingCondition when =
        new ModelPricingCondition(null, null, null, null, condition, null, null, null);
    ModelPricing pricing =
        new ModelPricing(
            new ModelPricing.RateSet(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null),
            List.of(
                new ModelPricing.Override(
                    "fast",
                    when,
                    new ModelPricing.RatePatch(
                        BigDecimal.ONE, null, null, null, null, null, false))),
            List.of());
    ModelRequestBodyPricing.Plan plan = ModelRequestBodyPricing.plan(pricing);
    String fingerprint = ModelRequestBodyPricing.fingerprint(condition);
    AtomicInteger attempts = new AtomicInteger();
    PlatformModelGateway gateway =
        new PlatformModelGateway(
            new Resolution(
                scripted(
                    (request, handler) -> {
                      handler.onRequestBody(
                          "{\"speed\":\"fast\"}".getBytes(StandardCharsets.UTF_8));
                      handler.onComplete(new ProviderCompletion(response()));
                    })),
            () -> BUSY_DELAY,
            new ConcurrencyAdmission(2),
            modelName ->
                attempts.getAndIncrement() == 0
                    ? plan
                    : ModelRequestBodyPricing.plan(ModelPricing.ZERO));

    ModelGateway.Succeeded first = assertInstanceOf(ModelGateway.Succeeded.class, run(gateway));
    assertEquals(
        Map.of(fingerprint, true), first.completion().response().requestBodyPricing().matches());

    ModelGateway.Succeeded second = assertInstanceOf(ModelGateway.Succeeded.class, run(gateway));
    assertNull(second.completion().response().requestBodyPricing());
  }

  /** 未实现观察回调的 adapter 不产生定价事实；伪造的 completion 快照会被 gateway 清除。 */
  @Test
  void missingObservationStaysUnknown() {
    ModelPricingCondition.RequestBody condition =
        ModelPricingCondition.RequestBody.stringValue("/speed", "fast");
    String fingerprint = ModelRequestBodyPricing.fingerprint(condition);
    ProviderResponse forged =
        response()
            .withRequestBodyPricing(new RequestBodyPricingSnapshot(Map.of(fingerprint, true)));
    PlatformModelGateway gateway =
        new PlatformModelGateway(
            new Resolution(
                scripted((request, handler) -> handler.onComplete(new ProviderCompletion(forged)))),
            () -> BUSY_DELAY,
            new ConcurrencyAdmission(1),
            modelName ->
                ModelRequestBodyPricing.plan(
                    new ModelPricing(
                        ModelPricing.RateSet.ZERO,
                        List.of(
                            new ModelPricing.Override(
                                "fast",
                                new ModelPricingCondition(
                                    null, null, null, null, condition, null, null, null),
                                new ModelPricing.RatePatch(
                                    BigDecimal.ONE, null, null, null, null, null, false))),
                        List.of())));
    ModelGateway.Succeeded succeeded = assertInstanceOf(ModelGateway.Succeeded.class, run(gateway));
    assertNull(succeeded.completion().response().requestBodyPricing());
  }

  private static ModelGateway.Outcome run(PlatformModelGateway gateway) {
    ModelGateway.Admitted admitted =
        assertInstanceOf(ModelGateway.Admitted.class, gateway.admit(EXECUTION));
    return admitted.run(event -> {});
  }

  private static PlatformModelGateway gateway(
      Resolution resolution, ConcurrencyAdmission admission) {
    return new PlatformModelGateway(
        resolution,
        () -> BUSY_DELAY,
        admission,
        modelName -> ModelRequestBodyPricing.plan(ModelPricing.ZERO));
  }

  private static Function<ModelCallTimeoutPolicy, ModelProvider> scripted(
      BiConsumer<ProviderRequest, ProviderStreamHandler> body) {
    return policy -> (request, handler) -> body.accept(request, handler);
  }

  private static ProviderRequest providerRequest() {
    return providerRequest("frozen-model");
  }

  private static ProviderRequest providerRequest(String modelId) {
    return new ProviderRequest(
        new ModelDescriptor(
            "provider", modelId, modelId, Set.of(ModelInputModality.TEXT), true, false),
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderResponse response() {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, Map.of(), 0L, 2L);
    return new ProviderResponse(
        "completed", "", List.of(), GenerationStopReason.COMPLETE, usage, null, null, "{}");
  }

  private static ProviderReplayState replayState() {
    var payload = JsonNodeFactory.instance.objectNode().put("role", "model");
    return new ProviderReplayState(
        ProviderReplayFormat.GEMINI_CONTENT,
        new ProviderReplayAffinity(
            ProviderType.GOOGLE, "google", CONNECTION_GENERATION_ID, "gemini-3.8-flash"),
        payload);
  }

  /** 可注入解析失败的 {@link ProviderResolutionService}，记录解析与打开 Provider 的次数和超时策略。 */
  private static final class Resolution implements ProviderResolutionService {

    private final AtomicInteger resolveCount = new AtomicInteger();
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicReference<ModelCallTimeoutPolicy> openedPolicy = new AtomicReference<>();
    private final Function<ModelCallTimeoutPolicy, ModelProvider> opener;
    private final ProviderRequest effectiveRequest;
    private RuntimeException resolveFailure;

    private Resolution(Function<ModelCallTimeoutPolicy, ModelProvider> opener) {
      this(null, opener);
    }

    private Resolution(
        ProviderRequest effectiveRequest, Function<ModelCallTimeoutPolicy, ModelProvider> opener) {
      this.effectiveRequest = effectiveRequest;
      this.opener = opener;
    }

    @Override
    public ResolvedExecution resolve(
        ProviderType frozenType, UUID frozenConnectionGenerationId, ProviderRequest request) {
      resolveCount.incrementAndGet();
      if (resolveFailure != null) {
        throw resolveFailure;
      }
      return new ResolvedExecution(
          effectiveRequest == null ? request : effectiveRequest,
          TIMEOUT_POLICY,
          policy -> {
            openCount.incrementAndGet();
            openedPolicy.set(policy);
            return opener.apply(policy);
          },
          ignored -> {
            throw new UnsupportedOperationException("request body preview is not under test");
          });
    }
  }
}
