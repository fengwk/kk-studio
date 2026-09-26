package fun.fengwk.kkstudio.harness.provider.openai.responses;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证 OpenAI Responses 流式桥接器的事件分发、生命周期状态转换与取消安全性。 */
class OpenAiResponsesStreamBridgeTest {

  private static ProviderCompletion createDummyCompletion() {
    ModelUsage usage = new ModelUsage(10, 10, 0, 0, 0, 0, 20);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ProviderResponse resp =
        new ProviderResponse(
            "ok", "", List.of(), GenerationStopReason.COMPLETE, usage, cost, "id", null, "{}");
    return new ProviderCompletion(resp, null);
  }

  /** 验证正常事件与完成回调顺利交付给 handler。 */
  @Test
  void test_normalDispatchAndComplete() {
    List<ProviderStreamEvent> events = new ArrayList<>();
    AtomicBoolean completed = new AtomicBoolean(false);

    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            events.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completed.set(true);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hello"));
    bridge.emitComplete(createDummyCompletion());

    assertEquals(1, events.size());
    assertTrue(completed.get());
    assertFalse(bridge.isCancelled());
  }

  /** 验证主动 cancel 之后阻断后续事件且状态变为已取消。 */
  @Test
  void test_cancelBlocksFurtherEvents() {
    List<ProviderStreamEvent> events = new ArrayList<>();
    AtomicBoolean completed = new AtomicBoolean(false);

    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            events.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completed.set(true);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("first"));
    bridge.cancel();
    assertTrue(bridge.isCancelled());

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("second"));
    bridge.emitComplete(createDummyCompletion());

    assertEquals(1, events.size());
    assertFalse(completed.get());
  }

  /** 验证在绑定底座流之前取消时，后绑定的底座流会立刻被 cancel。 */
  @Test
  void test_preBindCancelPropagatesImmediately() {
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);
    bridge.cancel();

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
    assertTrue(underlyingCancelled.get());
  }

  /** 验证 emitError 触发底层流取消并保证 terminal-once 语义。 */
  @Test
  void test_emitErrorTerminalOnce() {
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean underlyingCancelled = new AtomicBoolean(false);

    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            errorCount.incrementAndGet();
          }
        };

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);
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

    ProviderException pe = new ProviderException(ProviderErrorKind.TRANSIENT, "fail");
    bridge.emitError(pe);
    bridge.emitError(pe); // 重复触发被丢弃

    assertTrue(underlyingCancelled.get());
    assertEquals(1, errorCount.get());
  }

  /** 验证在 handler 回调内重入 cancel 线程安全且正常生效。 */
  @Test
  void test_reentrantCancelFromHandler() {
    AtomicBoolean cancelledInside = new AtomicBoolean(false);
    ProviderStreamHandler handler =
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
        };

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("trigger"));

    assertTrue(cancelledInside.get());
    assertTrue(bridge.isCancelled());
  }

  /** 测试意图：原生协议帧与规范化增量共用同一派发闸门 —— 取消与 terminal 后一律不再派发，重复帧信号不产生额外回调。 */
  @Test
  void test_protocolEventSharesDispatchGate() {
    List<ProviderProtocolEvent> protocolEvents = new ArrayList<>();

    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
            protocolEvents.add(event);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    OpenAiResponsesStreamBridge bridge = new OpenAiResponsesStreamBridge(handler);
    bridge.emitProtocolEvent(new ProviderProtocolEvent("response.created", "{}"));
    assertEquals(1, protocolEvents.size());

    // terminal-once：complete 之后原生帧不再派发，且原生帧本身不改变 terminal 状态
    bridge.emitComplete(createDummyCompletion());
    bridge.emitProtocolEvent(new ProviderProtocolEvent("response.completed", "{}"));
    assertEquals(1, protocolEvents.size());

    // cancel 之后同样不再派发
    bridge.cancel();
    bridge.emitProtocolEvent(new ProviderProtocolEvent("response.error", "{}"));
    assertEquals(1, protocolEvents.size());
    assertEquals("{}", protocolEvents.get(0).data());
  }
}
