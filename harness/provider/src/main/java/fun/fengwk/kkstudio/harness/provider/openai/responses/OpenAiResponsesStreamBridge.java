package fun.fengwk.kkstudio.harness.provider.openai.responses;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OpenAI Responses 流式生命周期桥接器。
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
final class OpenAiResponsesStreamBridge implements ProviderStream {

  private final ProviderStreamHandler handler;
  private final ReentrantLock dispatchLock = new ReentrantLock();
  private final Object bindLock = new Object();

  private volatile boolean userCancelled = false;
  private boolean transportCancelled = false;
  private boolean terminal = false;
  private ProviderStream underlyingStream = null;

  OpenAiResponsesStreamBridge(ProviderStreamHandler handler) {
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  void bind(ProviderStream stream) {
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

  @Override
  public boolean isCancelled() {
    synchronized (bindLock) {
      return this.userCancelled;
    }
  }

  void emitEvent(ProviderStreamEvent event) {
    Objects.requireNonNull(event, "event");
    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      handler.onEvent(event, this);
    } finally {
      dispatchLock.unlock();
    }
  }

  /**
   * 派发一条厂商原生协议帧。
   *
   * <p>与规范化增量共用同一 {@code dispatchLock}、取消状态与 terminal-once 语义：取消或已终止后不再派发，回调内重入取消线程安全。原生帧是
   * attempt-only 观测通道，不改变 terminal 状态，也不进入 durable checkpoint。
   */
  void emitProtocolEvent(ProviderProtocolEvent event) {
    Objects.requireNonNull(event, "event");
    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      handler.onProtocolEvent(event, this);
    } finally {
      dispatchLock.unlock();
    }
  }

  void emitComplete(ProviderCompletion completion) {
    Objects.requireNonNull(completion, "completion");
    dispatchLock.lock();
    try {
      if (this.userCancelled || this.terminal) {
        return;
      }
      this.terminal = true;
      handler.onComplete(completion, this);
    } finally {
      dispatchLock.unlock();
    }
  }

  void emitError(ProviderException error) {
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
      handler.onError(error, this);
    } finally {
      dispatchLock.unlock();
    }
  }
}
