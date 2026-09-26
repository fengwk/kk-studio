package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试意图：验证 OpenAI Chat 流式桥接器的取消保护、线程安全以及严格的 terminal-once 语义。 */
class OpenAiChatStreamBridgeTest {

  @Test
  @DisplayName("正常事件派发与完成回调")
  void normalDispatchAndComplete() {
    AtomicInteger eventCount = new AtomicInteger();
    AtomicBoolean completed = new AtomicBoolean();

    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            eventCount.incrementAndGet();
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completed.set(true);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    OpenAiChatStreamBridge bridge = new OpenAiChatStreamBridge(handler);
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hi"));
    assertEquals(1, eventCount.get());

    ModelUsage usage = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
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
    ProviderResponse response =
        new ProviderResponse(
            "hi", "", List.of(), GenerationStopReason.COMPLETE, usage, cost, "id", null, "{}");
    bridge.emitComplete(new ProviderCompletion(response, null));

    assertTrue(completed.get());

    // 终态后不再派发事件
    bridge.emitEvent(new ProviderStreamEvent.TextDelta("more"));
    assertEquals(1, eventCount.get());
  }

  @Test
  @DisplayName("Pre-bind cancel 导致 late-bind transport 立即 cancel")
  void preBindCancelCausesLateBindCancel() {
    AtomicBoolean handlerCalled = new AtomicBoolean();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            handlerCalled.set(true);
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            handlerCalled.set(true);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            handlerCalled.set(true);
          }
        };

    OpenAiChatStreamBridge bridge = new OpenAiChatStreamBridge(handler);
    bridge.cancel();
    assertTrue(bridge.isCancelled());

    AtomicBoolean underlyingCancelled = new AtomicBoolean();
    ProviderStream underlying =
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

    bridge.bind(underlying);
    assertTrue(underlyingCancelled.get());

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("test"));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "error"));
    assertFalse(handlerCalled.get());
  }

  @Test
  @DisplayName("native 协议事件与 normalized 增量共用 cancel/terminal 闸门")
  void protocolEventSharesCancelAndTerminalGating() {
    AtomicInteger protocolEventCount = new AtomicInteger();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event, ProviderStream stream) {
            protocolEventCount.incrementAndGet();
          }

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    OpenAiChatStreamBridge bridge = new OpenAiChatStreamBridge(handler);
    bridge.emitProtocolEvent(new ProviderProtocolEvent("chat.completion.chunk", "{\"id\":\"c\"}"));
    assertEquals(1, protocolEventCount.get());

    ModelUsage usage = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
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
    ProviderResponse response =
        new ProviderResponse(
            "hi", "", List.of(), GenerationStopReason.COMPLETE, usage, cost, "id", null, "{}");
    bridge.emitComplete(new ProviderCompletion(response, null));

    // 终态之后不再派发 native 事件
    bridge.emitProtocolEvent(new ProviderProtocolEvent("chat.completion.chunk", "{\"id\":\"c\"}"));
    assertEquals(1, protocolEventCount.get());

    // cancel 之后不再派发 native 事件
    OpenAiChatStreamBridge cancelledBridge = new OpenAiChatStreamBridge(handler);
    cancelledBridge.cancel();
    cancelledBridge.emitProtocolEvent(
        new ProviderProtocolEvent("chat.completion.chunk", "{\"id\":\"c\"}"));
    assertEquals(1, protocolEventCount.get());
  }
}
