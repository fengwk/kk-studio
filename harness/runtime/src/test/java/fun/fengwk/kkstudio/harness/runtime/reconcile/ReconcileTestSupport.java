package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.kernel.execution.Lease;
import fun.fengwk.kkstudio.harness.kernel.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.kernel.thread.InputStatus;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 共享的 reconcile 测试 fixture：Thread、Lease、ThreadInput、ProviderRequest、Clock 工厂。 */
final class ReconcileTestSupport {

  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private ReconcileTestSupport() {}

  static HarnessThread thread(long threadId, long epoch, String token) {
    return thread(threadId, epoch, token, 1L);
  }

  static HarnessThread thread(long threadId, long epoch, String token, long headEntryId) {
    Lease lease = new Lease(token, NOW.plusSeconds(60));
    return new HarnessThread(threadId, 100L, headEntryId, 0L, true, epoch, lease, NOW, NOW);
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
            List.of(),
            pricing,
            PromptCachePolicy.automatic(PromptCacheCapability.automatic()));
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(model, variant, List.of(), List.of(), ProviderCacheControl.none());
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
