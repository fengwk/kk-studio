package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ProviderStreamBridge} 权威生命周期契约测试。
 *
 * <p>四个协议适配器共用同一个桥接器，所以它的 terminal-once 与 handler 回调封口语义只在这里锁定一次；各协议自己的原生事件次序与
 * 端到端取消语义由对应协议包的集成测试覆盖。
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
              public void onEvent(ProviderStreamEvent event) {
                events.add(event);
              }

              @Override
              public void onComplete(ProviderCompletion completion) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error) {
                errorCount.incrementAndGet();
              }
            });

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("hi"));
    assertEquals(1, events.size());
    assertFalse(bridge.handlerFailed());

    bridge.emitComplete(dummyCompletion());
    assertEquals(1, completedCount.get());

    bridge.emitEvent(new ProviderStreamEvent.TextDelta("more"));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "late error"));
    assertEquals(1, events.size());
    assertEquals(0, errorCount.get());
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
              public void onEvent(ProviderStreamEvent event) {}

              @Override
              public void onComplete(ProviderCompletion completion) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error) {
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
              public void onEvent(ProviderStreamEvent event) {}

              @Override
              public void onComplete(ProviderCompletion completion) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error) {
                errorCount.incrementAndGet();
              }
            });

    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "first"));
    bridge.emitError(new ProviderException(ProviderErrorKind.TRANSIENT, "second"));
    bridge.emitComplete(dummyCompletion());

    assertEquals(1, errorCount.get());
    assertEquals(0, completedCount.get());
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
              public void onEvent(ProviderStreamEvent event) {}

              @Override
              public void onProtocolEvent(ProviderProtocolEvent event) {
                protocolEvents.add(event);
              }

              @Override
              public void onComplete(ProviderCompletion completion) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error) {}
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
  }

  /** 测试意图：handler 抛出的异常必须原样传播，同时把流封口并标记 handlerFailed，使随后的传输失败不再二次进入 onError。 */
  @Test
  void handlerThrowSealsTerminalAndReportsHandlerFailure() {
    AtomicInteger completedCount = new AtomicInteger();
    AtomicInteger errorCount = new AtomicInteger();
    ProviderStreamBridge bridge =
        new ProviderStreamBridge(
            new ProviderStreamHandler() {
              @Override
              public void onEvent(ProviderStreamEvent event) {
                throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "handler failed");
              }

              @Override
              public void onComplete(ProviderCompletion completion) {
                completedCount.incrementAndGet();
              }

              @Override
              public void onError(ProviderException error) {
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

  private static ProviderCompletion dummyCompletion() {
    ProviderResponse response =
        new ProviderResponse(
            "text",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            new ModelUsage(1, 1, 0, 0, Map.of(), 0L, 2L),
            "req1",
            "tier1",
            "{}");
    return new ProviderCompletion(response);
  }

  private static class NoopHandler implements ProviderStreamHandler {

    @Override
    public void onEvent(ProviderStreamEvent event) {}

    @Override
    public void onComplete(ProviderCompletion completion) {}

    @Override
    public void onError(ProviderException error) {}
  }
}
