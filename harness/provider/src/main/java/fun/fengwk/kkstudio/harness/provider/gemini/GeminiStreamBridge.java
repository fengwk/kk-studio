package fun.fengwk.kkstudio.harness.provider.gemini;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gemini 流式生命周期桥接器。
 *
 * <p>线程安全保证：
 *
 * <ul>
 *   <li>Pre-bind cancel guard 与 late transport bind 立即 cancel；
 *   <li>bind 与 cancel 线性化，消除竞态与死锁；
 *   <li>cancel 返回后不再调用 handler 任何回调；
 *   <li>complete / error / cancel 严格 terminal-once；
 *   <li>handler 回调内部重入 cancel 线程安全。
 * </ul>
 */
final class GeminiStreamBridge implements ProviderStream {

  private final ProviderStreamHandler handler;
  private final ReentrantLock dispatchLock = new ReentrantLock();
  private final Object bindLock = new Object();

  private volatile boolean userCancelled = false;
  private boolean transportCancelled = false;
  private boolean terminal = false;
  private ProviderStream underlyingStream = null;

  GeminiStreamBridge(ProviderStreamHandler handler) {
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

  /** 交付一条厂商原生协议事件；与规范化增量共用 dispatchLock，cancel 或终态后静默丢弃。 */
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

  @Override
  public String toString() {
    return "GeminiStreamBridge[userCancelled=" + userCancelled + ", terminal=" + terminal + "]";
  }
}
