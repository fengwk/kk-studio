package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证 AnthropicStreamBridge 的 Pre-bind 竞态与 Terminal-Once 契约。 */
class AnthropicStreamBridgeTest {

  @Test
  void cancelBeforeBindCancelsUnderlyingImmediatelyUponBind() {
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(new NoopHandler());
    assertFalse(bridge.isCancelled());

    // 提前取消
    bridge.cancel();
    assertTrue(bridge.isCancelled());

    // 随后 bind
    AtomicBoolean underlyingCancelled = new AtomicBoolean(false);
    ProviderStream mockUnderlying =
        new ProviderStream() {
          @Override
          public void cancel() {
            underlyingCancelled.set(true);
          }

          @Override
          public boolean isCancelled() {
            return underlyingCancelled.get();
          }
        };

    bridge.bind(mockUnderlying);
    assertTrue(
        underlyingCancelled.get(), "underlying stream must be cancelled immediately on bind");
  }

  @Test
  void guaranteesTerminalOnceAcrossCompleteAndError() {
    AtomicInteger completedCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    ProviderStreamHandler countingHandler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completedCount.incrementAndGet();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorCount.incrementAndGet();
          }
        };

    AnthropicStreamBridge bridge = new AnthropicStreamBridge(countingHandler);

    ProviderResponse resp =
        new ProviderResponse(
            "ok",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 1, 0, 0, 0, 0, 0),
            zeroCost(),
            null,
            null,
            "{}");
    ProviderCompletion completion = new ProviderCompletion(resp, null);

    // 尝试触发多次 complete 和 error
    bridge.emitComplete(completion);
    bridge.emitComplete(completion);
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "error"));

    assertEquals(1, completedCount.get());
    assertEquals(0, errorCount.get());
  }

  @Test
  void remainsSilentAfterCancellation() {
    AtomicBoolean terminalEmitted = new AtomicBoolean(false);
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            terminalEmitted.set(true);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            terminalEmitted.set(true);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            terminalEmitted.set(true);
          }
        };

    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    bridge.cancel();

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hello"));
    ProviderResponse resp =
        new ProviderResponse(
            "ok",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 1, 0, 0, 0, 0, 0),
            zeroCost(),
            null,
            null,
            "{}");
    bridge.emitComplete(new ProviderCompletion(resp, null));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "err"));

    assertFalse(terminalEmitted.get(), "no callbacks should be emitted after cancel");
  }

  @Test
  void suppressesCallbacksWhileUnderlyingCancellationIsStillInProgress() throws Exception {
    AtomicInteger eventCount = new AtomicInteger();
    CountDownLatch underlyingCancelStarted = new CountDownLatch(1);
    CountDownLatch releaseUnderlyingCancel = new CountDownLatch(1);
    AnthropicStreamBridge bridge =
        new AnthropicStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
                eventCount.incrementAndGet();
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });
    bridge.bind(
        new ProviderStream() {
          @Override
          public void cancel() {
            underlyingCancelStarted.countDown();
            try {
              releaseUnderlyingCancel.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          }

          @Override
          public boolean isCancelled() {
            return false;
          }
        });

    Thread cancelThread = new Thread(bridge::cancel);
    cancelThread.start();
    try {
      assertTrue(underlyingCancelStarted.await(5, TimeUnit.SECONDS));
      bridge.emitEvent(new ProviderStreamEvent.TextDelta("late"));
      assertEquals(0, eventCount.get());
    } finally {
      releaseUnderlyingCancel.countDown();
      cancelThread.join(TimeUnit.SECONDS.toMillis(5));
    }
    assertFalse(cancelThread.isAlive());
  }

  @Test
  void emitErrorCancelsUnderlyingStreamExactlyOnceAndLateBindCancelsImmediately() {
    AtomicInteger cancelCount = new AtomicInteger(0);
    ProviderStream mockUnderlying =
        new ProviderStream() {
          @Override
          public void cancel() {
            cancelCount.incrementAndGet();
          }

          @Override
          public boolean isCancelled() {
            return cancelCount.get() > 0;
          }
        };

    // 1. 先 bind，后 emitError
    AnthropicStreamBridge bridge1 = new AnthropicStreamBridge(new NoopHandler());
    bridge1.bind(mockUnderlying);
    assertEquals(0, cancelCount.get());

    bridge1.emitError(new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid SSE"));
    assertEquals(1, cancelCount.get(), "underlying stream must be cancelled on protocol error");
    assertFalse(
        bridge1.isCancelled(),
        "public isCancelled should remain false when user did not call cancel");

    // 重复触发 error 不得再次 cancel
    bridge1.emitError(new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "second error"));
    assertEquals(1, cancelCount.get(), "underlying cancel must occur exactly once");

    // 2. 先 emitError（error-before-bind），后 late bind
    AtomicInteger lateCancelCount = new AtomicInteger(0);
    ProviderStream lateUnderlying =
        new ProviderStream() {
          @Override
          public void cancel() {
            lateCancelCount.incrementAndGet();
          }

          @Override
          public boolean isCancelled() {
            return lateCancelCount.get() > 0;
          }
        };

    AnthropicStreamBridge bridge2 = new AnthropicStreamBridge(new NoopHandler());
    bridge2.emitError(new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "pre-bind error"));
    assertEquals(0, lateCancelCount.get());

    bridge2.bind(lateUnderlying);
    assertEquals(
        1,
        lateCancelCount.get(),
        "late bind must cancel immediately when error occurred before bind");
  }

  @Test
  void bindRejectsDuplicateDifferentStream() {
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(new NoopHandler());
    ProviderStream stream1 =
        new ProviderStream() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };
    ProviderStream stream2 =
        new ProviderStream() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };

    bridge.bind(stream1);
    // 允许同一 stream 重复 bind
    bridge.bind(stream1);

    // 拒绝不同 stream 重复 bind
    assertThrows(IllegalStateException.class, () -> bridge.bind(stream2));
  }

  private static ModelCost zeroCost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static class NoopHandler implements ProviderStreamHandler {
    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

    @Override
    public void onError(ProviderException error, ProviderStream stream) {}
  }
}
