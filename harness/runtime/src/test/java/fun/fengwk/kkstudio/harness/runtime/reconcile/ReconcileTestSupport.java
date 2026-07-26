package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.EnvironmentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ExecutionPolicySnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 共享的 runtime 测试 fixture：Thread、Lease、ThreadInput、ProviderRequest、Clock 工厂。 */
public final class ReconcileTestSupport {

  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private ReconcileTestSupport() {}

  static HarnessThread thread(long threadId, long epoch, String token) {
    return thread(threadId, epoch, token, 1L);
  }

  static HarnessThread thread(long threadId, long epoch, String token, long headEntryId) {
    Lease lease = new Lease(token, NOW.plusSeconds(60));
    return new HarnessThread(threadId, headEntryId, 0L, true, epoch, lease, NOW, NOW);
  }

  static ThreadOwnership ownership(long threadId, long epoch, String token) {
    return new ThreadOwnership(threadId, epoch, token);
  }

  static ThreadInput input(long threadId, long sequence, ThreadInputType type) {
    ThreadInputPayload payload = () -> type;
    return new ThreadInput(
        sequence,
        threadId,
        sequence,
        type,
        payload,
        "key-" + sequence,
        InputStatus.QUEUED,
        NOW,
        null);
  }

  static ProviderRequest providerRequest() {
    BigDecimal one = new BigDecimal("1");
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "default",
            "standard",
            one,
            "v1",
            one,
            one,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor model =
        new ModelDescriptor(
            1L,
            2L,
            ProviderType.OPENAI,
            "gpt-test",
            "Test",
            8_192L,
            4_096L,
            Set.of(ModelInputModality.TEXT),
            false,
            false,
            List.of(variant),
            pricing,
            PromptCachePolicy.automatic(PromptCacheCapability.automatic()));
    return new ProviderRequest(model, variant, List.of(), List.of(), ProviderCacheControl.none());
  }

  public static RuntimeConfigSnapshot configSnapshot() {
    ProviderRequest request = providerRequest();
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(1L, "agent", "system"),
        new ModelSnapshot(request.model(), request.variant()),
        List.of(),
        List.of(),
        new ExecutionPolicySnapshot(1, 1, 1, null, List.of(), false),
        new EnvironmentSnapshot(null, "workspace"));
  }

  /**
   * 单调递增的测试 {@link Clock}：每次 {@link #instant()} 调用都向前推进 {@code step}，便于断言 Reconciler 在多次 mutation
   * 中读取到不同的当前时刻。
   */
  static final class MutableClock extends Clock {
    private Instant current;
    private final Duration step;
    private final ZoneId zone;

    MutableClock(Instant initial) {
      this(initial, Duration.ofMillis(1));
    }

    MutableClock(Instant initial, Duration step) {
      this(initial, step, ZoneOffset.UTC);
    }

    private MutableClock(Instant initial, Duration step, ZoneId zone) {
      this.current = Objects.requireNonNull(initial, "initial");
      this.step = Objects.requireNonNull(step, "step");
      if (step.isZero() || step.isNegative()) {
        throw new IllegalArgumentException("step must be positive");
      }
      this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public Instant instant() {
      Instant result = current;
      current = current.plus(step);
      return result;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return zone.equals(this.zone) ? this : new MutableClock(current, step, zone);
    }
  }
}
