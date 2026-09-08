package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证 GeminiStreamBridge 的生命周期状态机、Pre-bind 取消与 Terminal-Once 契约。 */
class GeminiStreamBridgeTest {

  private static ProviderCompletion dummyCompletion() {
    ProviderResponse resp =
        new ProviderResponse(
            "text",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 1, 0, 0, 0, 0, 2),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            "req1",
            "tier1",
            "{}",
            List.of());
    return new ProviderCompletion(resp);
  }

  /** 验证在底层传输流绑定前调用 cancel，随后绑定时底层流会被立即触发 cancel。 */
  @Test
  void cancelBeforeBindCancelsUnderlyingImmediatelyUponBind() {
    AtomicInteger eventCount = new AtomicInteger();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            eventCount.incrementAndGet();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };

    GeminiStreamBridge bridge = new GeminiStreamBridge(handler);
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
    assertTrue(underlyingCancelled.get(), "underlying stream must be cancelled upon bind");

    // 取消后再发 event，绝不交付给 handler
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hello"));
    assertEquals(0, eventCount.get());
  }

  /** 验证已绑定底层流后调用 cancel，底层流立即被取消。 */
  @Test
  void cancelAfterBindCancelsUnderlying() {
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };

    GeminiStreamBridge bridge = new GeminiStreamBridge(handler);
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
    assertFalse(bridge.isCancelled());
    assertFalse(underlyingCancelled.get());

    bridge.cancel();
    assertTrue(bridge.isCancelled());
    assertTrue(underlyingCancelled.get());
  }

  /** 验证完成回调的 Terminal-Once 契约（至多回调一次，之后静默）。 */
  @Test
  void terminalOnce_completion() {
    AtomicInteger completeCount = new AtomicInteger();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completeCount.incrementAndGet();
          }
        };

    GeminiStreamBridge bridge = new GeminiStreamBridge(handler);
    bridge.emitComplete(dummyCompletion());
    bridge.emitComplete(dummyCompletion());
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "error"));

    assertEquals(1, completeCount.get());
  }

  /** 验证错误回调的 Terminal-Once 契约。 */
  @Test
  void terminalOnce_error() {
    AtomicInteger errorCount = new AtomicInteger();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorCount.incrementAndGet();
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };

    GeminiStreamBridge bridge = new GeminiStreamBridge(handler);
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "first"));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "second"));
    bridge.emitComplete(dummyCompletion());

    assertEquals(1, errorCount.get());
  }
}
