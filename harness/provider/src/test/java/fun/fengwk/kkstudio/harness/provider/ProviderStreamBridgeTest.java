package fun.fengwk.kkstudio.harness.provider;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ProviderStreamBridge} 权威生命周期契约测试。
 *
 * <p>四个协议适配器共用同一个桥接器，所以它的 bind、cancel、terminal-once 与 handler 回调封口语义只在这里锁定一次；各协议自己的
 * 原生事件次序与端到端取消语义由对应协议包的集成测试覆盖。并发用例用 latch 同步真实线程，不靠 sleep 抢时间。
 */
class ProviderStreamBridgeTest {

  /** 测试意图：正常路径下增量与 complete 各交付一次，complete 之后到达的增量、error 不再交付，未取消也未封口 handler 失败。 */
  @Test
  void normalDispatchAndComplete() {
    List<ProviderStreamEvent> events = new ArrayList<>();
    AtomicInteger completedCount = new AtomicInteger();
    AtomicInteger errorCount = new AtomicInteger();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
                events.add(event);
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error, ProviderStream stream) {
                errorCount.incrementAndGet();
              }
            });

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hi"));
    assertEquals(1, events.size());
    assertFalse(bridge.isCancelled());
    assertFalse(bridge.handlerFailed());

    bridge.emitComplete(dummyCompletion());
    assertEquals(1, completedCount.get());

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("more"));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "late error"));
    assertEquals(1, events.size());
    assertEquals(0, errorCount.get());
  }

  /** 测试意图：bind 之前取消时，late bind 的底层流必须立即被取消，且此后增量、原生帧、终态一律不再回调 handler。 */
  @Test
  void cancelBeforeBindCancelsUnderlyingImmediatelyUponBindAndStaysSilent() {
    AtomicBoolean handlerCalled = new AtomicBoolean();
    ProviderStreamBridge bridge = new ProviderStreamBridge(recordingHandler(handlerCalled));
    assertFalse(bridge.isCancelled());

    bridge.cancel();
    assertTrue(bridge.isCancelled());

    RecordingUnderlyingStream underlying = new RecordingUnderlyingStream(null);
    bridge.bind(underlying);
    assertEquals(
        1, underlying.cancelCount(), "underlying stream must be cancelled immediately on bind");

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hello"));
    bridge.emitProtocolEvent(new ProviderProtocolEvent("message", "{}"));
    bridge.emitComplete(dummyCompletion());
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "error"));
    assertFalse(handlerCalled.get(), "no callbacks should be delivered after cancel");
  }

  /** 测试意图：已绑定的底层流在 cancel 时立即被取消一次，绑定本身不触发取消。 */
  @Test
  void cancelAfterBindCancelsUnderlying() {
    ProviderStreamBridge bridge = new ProviderStreamBridge(new NoopHandler());
    RecordingUnderlyingStream underlying = new RecordingUnderlyingStream(null);

    bridge.bind(underlying);
    assertFalse(bridge.isCancelled());
    assertEquals(0, underlying.cancelCount(), "bind must not cancel the underlying stream");

    bridge.cancel();
    assertTrue(bridge.isCancelled());
    assertEquals(1, underlying.cancelCount());
  }

  /** 测试意图：cancel 之后到达的增量、complete 与 error 一律静默丢弃。 */
  @Test
  void remainsSilentAfterCancellation() {
    AtomicBoolean anyCallback = new AtomicBoolean();
    ProviderStreamBridge bridge = new ProviderStreamBridge(recordingHandler(anyCallback));
    bridge.bind(new RecordingUnderlyingStream(null));

    bridge.cancel();
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hello"));
    bridge.emitComplete(dummyCompletion());
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "err"));

    assertFalse(anyCallback.get(), "no callbacks should be emitted after cancel");
  }

  /** 测试意图：允许重复绑定同一底层流；绑定另一个实例必须以 IllegalStateException 拒绝。 */
  @Test
  void bindAllowsSameStreamRebindAndRejectsDifferentStream() {
    ProviderStreamBridge bridge = new ProviderStreamBridge(new NoopHandler());
    RecordingUnderlyingStream first = new RecordingUnderlyingStream(null);

    bridge.bind(first);
    bridge.bind(first);
    assertThrows(
        IllegalStateException.class, () -> bridge.bind(new RecordingUnderlyingStream(null)));
  }

  /** 测试意图：complete 是终态，随后的 complete 与 error 都不得再进入 handler。 */
  @Test
  void terminalOnceCompletionWinsOverLaterError() {
    AtomicInteger completedCount = new AtomicInteger();
    AtomicInteger errorCount = new AtomicInteger();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
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
            });

    bridge.emitComplete(dummyCompletion());
    bridge.emitComplete(dummyCompletion());
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "error"));

    assertEquals(1, completedCount.get());
    assertEquals(0, errorCount.get());
  }

  /** 测试意图：error 是终态，随后的 error 与 complete 都不得再进入 handler。 */
  @Test
  void terminalOnceErrorWinsOverLaterCompletion() {
    AtomicInteger completedCount = new AtomicInteger();
    AtomicInteger errorCount = new AtomicInteger();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
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
            });

    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "first"));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "second"));
    bridge.emitComplete(dummyCompletion());

    assertEquals(1, errorCount.get());
    assertEquals(0, completedCount.get());
  }

  /**
   * 测试意图：error 终态恰好取消底层流一次（重复 error 不再取消），且这不等于用户 cancel——公开 isCancelled 仍为 false； bind 之前发生 error
   * 时，late bind 必须立即取消底层流。
   */
  @Test
  void emitErrorCancelsUnderlyingExactlyOnceAndLateBindCancelsImmediately() {
    RecordingUnderlyingStream underlying = new RecordingUnderlyingStream(null);
    ProviderStreamBridge bound = new ProviderStreamBridge(new NoopHandler());
    bound.bind(underlying);
    assertEquals(0, underlying.cancelCount());

    bound.emitError(new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid SSE"));
    assertEquals(1, underlying.cancelCount(), "underlying stream must be cancelled on error");
    assertFalse(bound.isCancelled(), "public isCancelled must stay false when user did not cancel");

    bound.emitError(new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "second error"));
    assertEquals(1, underlying.cancelCount(), "underlying cancel must occur exactly once");

    RecordingUnderlyingStream lateUnderlying = new RecordingUnderlyingStream(null);
    ProviderStreamBridge errorBeforeBind = new ProviderStreamBridge(new NoopHandler());
    errorBeforeBind.emitError(
        new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "pre-bind"));
    assertEquals(0, lateUnderlying.cancelCount());

    errorBeforeBind.bind(lateUnderlying);
    assertEquals(
        1, lateUnderlying.cancelCount(), "late bind must cancel immediately after pre-bind error");
  }

  /** 测试意图：原生协议帧与规范化增量共用同一派发闸门——取消或终态后一律静默；原生帧本身不消耗终态，成功终态仍可交付。 */
  @Test
  void protocolEventSharesDispatchGateAndDoesNotChangeTerminalState() {
    List<ProviderProtocolEvent> protocolEvents = new ArrayList<>();
    AtomicInteger completedCount = new AtomicInteger();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

              @Override
              public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
                protocolEvents.add(event);
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });

    bridge.emitProtocolEvent(new ProviderProtocolEvent("message", "{\"id\":\"1\"}"));
    assertEquals(1, protocolEvents.size());
    assertEquals("message", protocolEvents.get(0).eventType());
    assertEquals("{\"id\":\"1\"}", protocolEvents.get(0).data());

    // 原生帧只做观测，不改变 terminal 状态：成功终态仍必须交付一次
    bridge.emitComplete(dummyCompletion());
    assertEquals(1, completedCount.get());

    // 终态之后不再派发原生帧
    bridge.emitProtocolEvent(new ProviderProtocolEvent("message", "{\"id\":\"2\"}"));
    assertEquals(1, protocolEvents.size());

    ProviderStreamBridge cancelled = new ProviderStreamBridge(new NoopHandler());
    cancelled.cancel();
    cancelled.emitProtocolEvent(new ProviderProtocolEvent("message", "{\"id\":\"3\"}"));
    assertEquals(1, protocolEvents.size());
  }

  /** 测试意图：底层流 cancel 仍在进行时，cancel 尚未返回，此间到达的增量必须静默；取消线程最终正常退出，不出现死锁。 */
  @Test
  void suppressesCallbacksWhileUnderlyingCancellationIsStillInProgress() throws Exception {
    AtomicInteger eventCount = new AtomicInteger();
    CountDownLatch underlyingCancelStarted = new CountDownLatch(1);
    CountDownLatch releaseUnderlyingCancel = new CountDownLatch(1);
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
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

  /** 测试意图：handler 回调内部重入 cancel 线程安全，且取消立即生效。 */
  @Test
  void reentrantCancelFromHandler() {
    AtomicBoolean cancelledInside = new AtomicBoolean(false);
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
                stream.cancel();
                cancelledInside.set(stream.isCancelled());
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

              @Override
              public void onError(ProviderException error, ProviderStream stream) {}
            });

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("trigger"));

    assertTrue(cancelledInside.get());
    assertTrue(bridge.isCancelled());
  }

  /** 测试意图：handler 抛出的异常必须原样传播，同时把流封口并标记 handlerFailed，使随后的 CALLBACK_FAILED 不再二次进入 onError。 */
  @Test
  void handlerThrowSealsTerminalAndReportsHandlerFailure() {
    AtomicInteger completedCount = new AtomicInteger();
    AtomicInteger errorCount = new AtomicInteger();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
                throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "handler failed");
              }

              @Override
              public void onComplete(ProviderCompletion completion, ProviderStream stream) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error, ProviderStream stream) {
                errorCount.incrementAndGet();
              }
            });

    assertThrows(
        ProviderException.class, () -> bridge.emitEvent(new ProviderStreamEvent.TextDelta("boom")));
    assertTrue(bridge.handlerFailed());

    bridge.emitComplete(dummyCompletion());
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "late transport error"));
    assertEquals(0, completedCount.get());
    assertEquals(0, errorCount.get());
  }

  private static ProviderStreamHandler recordingHandler(AtomicBoolean anyCallback) {
    return new ProviderStreamHandler() {
      @Override
      public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
        anyCallback.set(true);
      }

      @Override
      public void onComplete(ProviderCompletion completion, ProviderStream stream) {
        anyCallback.set(true);
      }

      @Override
      public void onError(ProviderException error, ProviderStream stream) {
        anyCallback.set(true);
      }
    };
  }

  private static ProviderCompletion dummyCompletion() {
    ProviderResponse response =
        new ProviderResponse(
            "text",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 1, 0, 0, 0, 0, 2),
            zeroCost(),
            "req1",
            "tier1",
            "{}");
    return new ProviderCompletion(response);
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

  /** 记录 cancel 次数的底层流替身；可在 cancel 内注入阻塞点以构造并发场景。 */
  private static final class RecordingUnderlyingStream implements ProviderStream {

    private final AtomicInteger cancelCount = new AtomicInteger();
    private final Runnable onCancel;

    private RecordingUnderlyingStream(Runnable onCancel) {
      this.onCancel = onCancel == null ? () -> {} : onCancel;
    }

    private int cancelCount() {
      return cancelCount.get();
    }

    @Override
    public void cancel() {
      cancelCount.incrementAndGet();
      onCancel.run();
    }

    @Override
    public boolean isCancelled() {
      return cancelCount.get() > 0;
    }
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
