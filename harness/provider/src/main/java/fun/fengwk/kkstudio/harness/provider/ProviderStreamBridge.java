package fun.fengwk.kkstudio.harness.provider;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 各协议 ModelProvider 共用的流式生命周期桥接器。
 *
 * <p>一次调用的所有回调都在同一个调用线程上顺序发生，因此本类不需要任何锁：它只收口 terminal-once（complete / error 至多一次且之后不再 派发任何回调）以及
 * handler 失败封口。它位于 provider 根包而非某个协议子包，因此对子包中的适配器与累积器可见 ——这是仅供模块内部复用的协议适配基础组件，不是对外扩展点，协议差异不得写进本类。
 */
public final class ProviderStreamBridge {

  private final ProviderStreamHandler handler;
  private boolean terminal;
  private boolean handlerFailed;

  public ProviderStreamBridge(ProviderStreamHandler handler) {
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  /** 用户 handler 是否已在回调中抛出异常。异常已封口时不得再尝试交付终态。 */
  public boolean handlerFailed() {
    return handlerFailed;
  }

  /** 已交付终态（或 handler 失败封口）：调用方应停止读取后续帧。 */
  public boolean isClosed() {
    return terminal;
  }

  /** 派发一条规范化增量；终态后静默丢弃。 */
  public void emitEvent(ProviderStreamEvent event) {
    Objects.requireNonNull(event, "event");
    if (isClosed()) {
      return;
    }
    dispatch(handler -> handler.onEvent(event));
  }

  /**
   * 派发一条厂商原生协议事件：每条 transport 帧的原生回调先于它派生的 normalized 增量。原生帧是 attempt-only 观测通道，不改变 terminal
   * 状态，也不进入 durable checkpoint。
   */
  public void emitProtocolEvent(ProviderProtocolEvent event) {
    Objects.requireNonNull(event, "event");
    if (isClosed()) {
      return;
    }
    dispatch(handler -> handler.onProtocolEvent(event));
  }

  /** 交付成功终态，并把流封口为 terminal-once；重复终态与后续增量一律静默丢弃。 */
  public void emitComplete(ProviderCompletion completion) {
    Objects.requireNonNull(completion, "completion");
    if (isClosed()) {
      return;
    }
    terminal = true;
    dispatch(handler -> handler.onComplete(completion));
  }

  /** 交付失败终态并封口。 */
  public void emitError(ProviderException error) {
    Objects.requireNonNull(error, "error");
    if (isClosed()) {
      return;
    }
    terminal = true;
    dispatch(handler -> handler.onError(error));
  }

  /** handler 抛出的任何异常（包括 ProviderException）都先封口再继续抛出，调用方据此不再重复交付终态。 */
  private void dispatch(Consumer<ProviderStreamHandler> callback) {
    try {
      callback.accept(handler);
    } catch (RuntimeException handlerFailure) {
      terminal = true;
      handlerFailed = true;
      throw handlerFailure;
    }
  }
}
