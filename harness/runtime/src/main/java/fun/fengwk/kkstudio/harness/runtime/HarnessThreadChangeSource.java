package fun.fengwk.kkstudio.harness.runtime;

import java.util.UUID;

/**
 * 内部 Thread durable revision change 唤醒源的最小 port。
 *
 * <p>消费者只关心「是否可能发生变化」的 wake 信号，不接收 revision 值；{@link #subscribe} 必须先在原子语义上完成注册再返回，之后发生的 revision
 * 变化（含 LISTEN 断线 resync）保证经 {@code onChange} 唤醒。缺少数值/游标返回：观察者总是订阅后再读权威 snapshot，事件的丢失由 「先注册再读
 * cursor」保证不存在竞态窗口。
 */
public interface HarnessThreadChangeSource {

  /** 原子注册一个 Thread 的 revision change 订阅并返回释放句柄；{@link #close()} 无 checked-exception 且幂等。 */
  Subscription subscribe(UUID threadId, Runnable onChange);

  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }
}
