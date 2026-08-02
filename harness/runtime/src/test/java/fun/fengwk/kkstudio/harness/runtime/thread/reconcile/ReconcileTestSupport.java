package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** 共享的 runtime 测试 fixture：Thread、Lease、ThreadInput、ProviderRequest、Clock 工厂。 */
public final class ReconcileTestSupport {

  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private ReconcileTestSupport() {}

  static HarnessThread thread(long threadId, long epoch, String token) {
    return thread(threadId, epoch, token, 1L);
  }

  static HarnessThread thread(long threadId, long epoch, String token, long headEntryId) {
    Lease lease = new Lease(token, NOW.plusSeconds(60));
    return new HarnessThread(threadId, headEntryId, 0L, true, epoch, 0L, lease, NOW, NOW);
  }

  static ThreadOwnership ownership(long threadId, long epoch, String token) {
    return new ThreadOwnership(threadId, epoch, token);
  }

  static ThreadInput input(long threadId, long sequence, ThreadInputType type) {
    TurnSettings settings = new TurnSettings("agent-" + threadId, "environment", false);
    ThreadInputPayload payload =
        switch (type) {
          case USER_MESSAGE -> new RuntimeEntryInputPayload(
              type,
              new MessageEntryPayload(
                  new AgentMessage(
                      AgentMessageRole.USER, List.of(new TextMessageContent("user-" + sequence))),
                  settings,
                  null));
          case CUSTOM_MESSAGE -> new RuntimeEntryInputPayload(
              type,
              new CustomMessageEntryPayload(
                  new AgentMessage(
                      AgentMessageRole.SYSTEM,
                      List.of(new TextMessageContent("custom-" + sequence))),
                  settings));
        };
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

  public static ProviderRequest providerRequest() {
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
            "provider-test",
            0L,
            "model-test",
            ProviderType.OPENAI,
            false,
            false,
            pricing,
            PromptCachePolicy.automatic(PromptCacheCapability.automatic()));
    return new ProviderRequest(model, variant, List.of(), List.of(), ProviderCacheControl.none());
  }

  public static ModelInvocationRequest modelInvocationRequest() {
    return new ModelInvocationRequest(providerRequest(), List.of(), List.of(), false);
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
