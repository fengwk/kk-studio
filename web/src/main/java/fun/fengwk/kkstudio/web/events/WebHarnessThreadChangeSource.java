package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 web 的 {@link ThreadVersionEventSource}（PostgreSQL LISTEN fan-out）适配为 core 内部 {@link
 * HarnessThreadChangeSource}。
 *
 * <p>直接透传既有「先注册再读 cursor、断线 resync」语义：本适配只把带 payload 的 version 事件折叠为纯 wake 信号，自身不维护第二套
 * LISTEN/订阅状态。返回句柄的 {@code close()} 幂等透传到底层 {@link SourceSubscribed#handle()}。
 */
final class WebHarnessThreadChangeSource implements HarnessThreadChangeSource {

  private final ThreadVersionEventSource delegate;

  WebHarnessThreadChangeSource(ThreadVersionEventSource delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public Subscription subscribe(UUID threadId, Runnable onChange) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(onChange, "onChange");
    SourceSubscribed subscribed = delegate.subscribe(threadId, ignored -> onChange.run());
    return new PassThroughSubscription(subscribed);
  }

  private static final class PassThroughSubscription implements Subscription {
    private final SourceSubscribed subscribed;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PassThroughSubscription(SourceSubscribed subscribed) {
      this.subscribed = subscribed;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          subscribed.handle().close();
        } catch (Exception error) {
          throw new IllegalStateException("cannot close thread change subscription", error);
        }
      }
    }
  }
}
