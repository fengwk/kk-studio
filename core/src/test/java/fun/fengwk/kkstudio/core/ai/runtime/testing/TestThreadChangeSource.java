package fun.fengwk.kkstudio.core.ai.runtime.testing;

import fun.fengwk.kkstudio.core.ai.runtime.HarnessThreadChangeSource;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用可控 internal Thread change source：记录订阅/释放，{@link #signal} 触发指定 thread 的存活订阅回调。
 *
 * <p>回调在 signal 调用线程同步执行，符合真实 source「先注册后送达、断线 resync」的最小语义；无存活订阅时 signal 为安全 no-op。
 */
public final class TestThreadChangeSource implements HarnessThreadChangeSource {

  private final Map<UUID, CopyOnWriteArrayList<SubscriptionImpl>> subscriptions =
      new ConcurrentHashMap<>();
  private final AtomicInteger subscribeCount = new AtomicInteger();
  private final AtomicInteger closeCount = new AtomicInteger();

  @Override
  public Subscription subscribe(UUID threadId, Runnable onChange) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(onChange, "onChange");
    subscribeCount.incrementAndGet();
    SubscriptionImpl impl = new SubscriptionImpl(threadId, onChange);
    subscriptions.computeIfAbsent(threadId, ignored -> new CopyOnWriteArrayList<>()).add(impl);
    return impl;
  }

  /** 触发指定 thread 的全部存活订阅回调；无订阅时安全 no-op。 */
  public void signal(UUID threadId) {
    CopyOnWriteArrayList<SubscriptionImpl> list = subscriptions.get(threadId);
    if (list != null) {
      for (SubscriptionImpl impl : list) {
        impl.fire();
      }
    }
  }

  public boolean isSubscribed(UUID threadId) {
    CopyOnWriteArrayList<SubscriptionImpl> list = subscriptions.get(threadId);
    return list != null && !list.isEmpty();
  }

  public int activeSubscriptions(UUID threadId) {
    CopyOnWriteArrayList<SubscriptionImpl> list = subscriptions.get(threadId);
    return list == null ? 0 : list.size();
  }

  public int totalSubscribes() {
    return subscribeCount.get();
  }

  public int totalClosed() {
    return closeCount.get();
  }

  private final class SubscriptionImpl implements Subscription {
    private final UUID threadId;
    private final Runnable onChange;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SubscriptionImpl(UUID threadId, Runnable onChange) {
      this.threadId = threadId;
      this.onChange = onChange;
    }

    void fire() {
      if (!closed.get()) {
        onChange.run();
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        CopyOnWriteArrayList<SubscriptionImpl> list = subscriptions.get(threadId);
        if (list != null) {
          list.remove(this);
          if (list.isEmpty()) {
            subscriptions.remove(threadId, list);
          }
        }
        closeCount.incrementAndGet();
      }
    }
  }
}
