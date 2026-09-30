package fun.fengwk.kkstudio.harness.provider;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 各协议 ModelProvider 共用的流式生命周期桥接器。
 *
 * <p>四个协议的差异只在 wire 编解码与错误分类；一条流的生命周期本身完全一致：本类把它们共用的 bind、cancel、terminal-once 与 handler
 * 回调封口收口到一处。它位于 provider 根包而非某个协议子包，因此对子包中的适配器与累积器可见——这是仅供模块内部复用的协议 适配基础组件，不是对外扩展点，协议差异不得写进本类。
 *
 * <p>线程安全保证：
 *
 * <ul>
 *   <li>Pre-bind cancel guard 与 late transport bind 立即 cancel；
 *   <li>bind 与 cancel 线性化，消除竞态与死锁；
 *   <li>cancel 返回后不再调用 handler 任何回调；
 *   <li>complete / error / cancel 严格 terminal-once；
 *   <li>规范化增量与原生协议帧共用同一派发闸门，二者顺序稳定；
 *   <li>handler 回调内部重入 cancel 线程安全。
 * </ul>
 */
public final class ProviderStreamBridge implements ProviderStream {

  private final ProviderStreamHandler handler;
  private final ReentrantLock dispatchLock = new ReentrantLock();
  private final Object bindLock = new Object();

  private volatile boolean userCancelled = false;
  private boolean transportCancelled = false;
  private boolean terminal = false;
  private boolean handlerFailed = false;
  private ProviderStream underlyingStream = null;

  public ProviderStreamBridge(ProviderStreamHandler handler) {
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  /**
   * 绑定 transport 返回的底层流。
   *
   * <p>允许重复绑定同一实例；已取消或已终止时，绑定后立即取消底层流，避免取消与绑定之间的竞态窗口漏掉取消。
   */
  public void bind(ProviderStream stream) {
    Objects.requireNonNull(stream, "stream");
    boolean needCancel = false;
    synchronized (bindLock) {
      if (this.underlyingStream != null && this.underlyingStream != stream) {
        throw new IllegalStateException("stream already bound");
      }
      this.underlyingStream = stream;
      if (this.userCancelled || this.transportCancelled) {
        needCancel = true;
      }
    }
    if (needCancel) {
      stream.cancel();
    }
  }

  @Override
  public void cancel() {
    ProviderStream streamToCancel = null;
    synchronized (bindLock) {
      if (!this.userCancelled) {
        this.userCancelled = true;
        if (!this.transportCancelled) {
          this.transportCancelled = true;
          streamToCancel = this.underlyingStream;
        }
      }
    }
    if (streamToCancel != null) {
      streamToCancel.cancel();
    }

    dispatchLock.lock();
    try {
      this.terminal = true;
    } finally {
      dispatchLock.unlock();
    }
  }

  /** 用户 handler 是否已在回调中抛出异常。异常已封口时 transport 回调不得再尝试交付终态。 */
  public boolean handlerFailed() {
    dispatchLock.lock();
    try {
      return this.handlerFailed;
    } finally {
      dispatchLock.unlock();
    }
  }

  @Override
  public boolean isCancelled() {
    synchronized (bindLock) {
      return this.userCancelled;
    }
  }

  /** 派发一条规范化增量；与原生协议帧共用 dispatchLock，cancel 或终态后静默丢弃。 */
  public void emitEvent(ProviderStreamEvent event) {
    Objects.requireNonNull(event, "event");
    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      dispatch(handler -> handler.onEvent(event, this));
    } finally {
      dispatchLock.unlock();
    }
  }

  /**
   * 派发一条厂商原生协议事件。
   *
   * <p>与规范化增量共用同一把 {@code dispatchLock} 与同一个 cancel / terminal 闸门：同一 attempt 内至多一次，cancel 或
   * terminal 之后不再派发，因此每条 transport 帧的原生回调与它派生的 normalized 增量保持「native 先于 normalized」且各自至多一次。原生帧是
   * attempt-only 观测通道，不改变 terminal 状态，也不进入 durable checkpoint。
   */
  public void emitProtocolEvent(ProviderProtocolEvent event) {
    Objects.requireNonNull(event, "event");
    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      dispatch(handler -> handler.onProtocolEvent(event, this));
    } finally {
      dispatchLock.unlock();
    }
  }

  /** 交付成功终态，并把流封口为 terminal-once；重复终态与后续增量一律静默丢弃。 */
  public void emitComplete(ProviderCompletion completion) {
    Objects.requireNonNull(completion, "completion");
    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      this.terminal = true;
      dispatch(handler -> handler.onComplete(completion, this));
    } finally {
      dispatchLock.unlock();
    }
  }

  /** 交付失败终态：先取消底层流（恰好一次，失败后 transport 无需再等取消），再在派发闸门下封口并回调 error。 */
  public void emitError(ProviderException error) {
    Objects.requireNonNull(error, "error");
    ProviderStream streamToCancel = null;
    synchronized (bindLock) {
      if (!this.transportCancelled) {
        this.transportCancelled = true;
        streamToCancel = this.underlyingStream;
      }
    }
    if (streamToCancel != null) {
      streamToCancel.cancel();
    }

    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      this.terminal = true;
      dispatch(handler -> handler.onError(error, this));
    } finally {
      dispatchLock.unlock();
    }
  }

  /**
   * 只包住 handler 回调。handler 抛出的任何异常（包括 ProviderException）都先封口再抛出，避免随后的 CALLBACK_FAILED 再次进入 onError。
   */
  private void dispatch(Consumer<ProviderStreamHandler> callback) {
    try {
      callback.accept(handler);
    } catch (RuntimeException handlerFailure) {
      this.terminal = true;
      this.handlerFailed = true;
      throw handlerFailure;
    }
  }
}
