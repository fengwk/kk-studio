package fun.fengwk.kkstudio.web.events;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * events 包内共享的组合式订阅容器：只共享 UUID 定点/全局注册、注销与消费者隔离 fan-out，不携带任何领域事件语义。
 *
 * <p>注册（{@code compute} 内 add 与集合创建原子完成）与最后注销（{@code computeIfPresent} 内 remove-if-empty 原子完成）在 同一
 * key 上串行化，杜绝「新订阅者加入已移除集合」的 detached subscriber；注销动作幂等。{@code key} 为 null 表示全局订阅（接收全部投递）。
 *
 * <p>{@link #publish} 只投递对应 key 与全局订阅者，{@link #broadcast} 投递全部订阅者且全局只投递一次。单个消费者抛出的 {@link
 * RuntimeException} 只隔离该消费者，不阻断其余消费者与后续通知；{@link Error} 不被吞掉。
 */
@Slf4j
final class SubscriptionFanOut<E> {

  private final Map<UUID, Set<Consumer<E>>> keyedSubscribers = new ConcurrentHashMap<>();
  private final Set<Consumer<E>> globalSubscribers = new CopyOnWriteArraySet<>();

  /** 注册订阅并返回幂等的注销动作；关闭只移除后续投递的订阅，不撤回已取得的 fan-out 快照。 */
  Runnable subscribe(UUID key, Consumer<E> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    if (key == null) {
      globalSubscribers.add(consumer);
      return once(() -> globalSubscribers.remove(consumer));
    }
    keyedSubscribers.compute(
        key,
        (id, existing) -> {
          Set<Consumer<E>> subscribers = existing != null ? existing : new CopyOnWriteArraySet<>();
          subscribers.add(consumer);
          return subscribers;
        });
    return once(() -> release(key, consumer));
  }

  /** 定点投递：对应 key 的订阅者与全部全局订阅者各收到一次。 */
  void publish(UUID key, E event) {
    for (Consumer<E> consumer : globalSubscribers) {
      deliver(consumer, event);
    }
    Set<Consumer<E>> subscribers = keyedSubscribers.get(key);
    if (subscribers != null) {
      for (Consumer<E> consumer : subscribers) {
        deliver(consumer, event);
      }
    }
  }

  /** 全量投递：全部订阅者各收到一次，全局订阅者只收到一次。 */
  void broadcast(E event) {
    for (Consumer<E> consumer : globalSubscribers) {
      deliver(consumer, event);
    }
    for (Set<Consumer<E>> subscribers : keyedSubscribers.values()) {
      for (Consumer<E> consumer : subscribers) {
        deliver(consumer, event);
      }
    }
  }

  private void release(UUID key, Consumer<E> consumer) {
    keyedSubscribers.computeIfPresent(
        key,
        (id, subscribers) -> {
          subscribers.remove(consumer);
          return subscribers.isEmpty() ? null : subscribers;
        });
  }

  private static Runnable once(Runnable release) {
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) {
        release.run();
      }
    };
  }

  private static <E> void deliver(Consumer<E> consumer, E event) {
    try {
      consumer.accept(event);
    } catch (RuntimeException error) {
      // 只报告受控阶段与异常类型，不回显 callback 异常正文。
      log.warn("subscription fan-out delivery failed; errorType={}", error.getClass().getName());
    }
  }
}
