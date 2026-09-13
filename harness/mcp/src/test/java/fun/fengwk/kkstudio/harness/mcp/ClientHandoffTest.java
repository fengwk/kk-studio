package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ClientHandoff} 所有权交接测试。
 *
 * <p>目标不变式：无论「构建完成」与「调用方放弃」以何种顺序交错，已构建的 client 都恰好被关闭一次，且绝不出现 0 次（泄漏）或 2 次（重复关闭）。
 */
class ClientHandoffTest {

  /** 可观测关闭次数的测试资源。 */
  private static final class Resource implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<String> events = new ArrayList<>();

    @Override
    public synchronized void close() {
      closed.set(true);
      events.add("close");
    }

    synchronized int closeCount() {
      return events.size();
    }
  }

  /** 正常路径：交付后调用方领取，交接本身不关闭资源。 */
  @Test
  void takenHandoffLeavesResourceOpen() {
    Resource resource = new Resource();
    ClientHandoff<Resource> handoff = new ClientHandoff<>();
    handoff.offer(resource);
    handoff.take();
    assertThat(resource.closeCount()).isZero();
  }

  /**
   * 构建方先交付、调用方后放弃：由放弃方关闭。
   *
   * <p>这正是「初始化恰好在预算到期之后完成」的交错：驱动线程已在等待超时，构建结果必须有唯一责任人。
   */
  @Test
  void abandonedAfterOfferClosesExactlyOnce() {
    Resource resource = new Resource();
    ClientHandoff<Resource> handoff = new ClientHandoff<>();
    handoff.offer(resource);
    handoff.abandon();
    assertThat(resource.closeCount()).isEqualTo(1);

    // 放弃之后 take() 不得复活该资源：所有权已归放弃方，调用方绝不能拿到一个已被关闭的 client
    handoff.take();
    assertThat(resource.closeCount()).isEqualTo(1);
  }

  /** 调用方先放弃、构建方后交付：由构建方当场关闭，绝不让 client 无人认领地持有子进程。 */
  @Test
  void offerAfterAbandonClosesExactlyOnce() {
    Resource resource = new Resource();
    ClientHandoff<Resource> handoff = new ClientHandoff<>();
    handoff.abandon();
    handoff.offer(resource);
    assertThat(resource.closeCount()).isEqualTo(1);
  }

  /**
   * 并发交错穷举：交付与放弃由 {@link CountDownLatch} 同时触发，断言无论谁先，关闭次数恒为 1。
   *
   * <p>覆盖真实竞态窗口，而不是依赖单一执行顺序的偶然结果。
   */
  @Test
  void concurrentOfferAndAbandonCloseExactlyOnce() throws Exception {
    int rounds = 5_000;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < rounds; round++) {
        Resource resource = new Resource();
        ClientHandoff<Resource> handoff = new ClientHandoff<>();
        CountDownLatch go = new CountDownLatch(1);

        Future<?> offering =
            pool.submit(
                () -> {
                  await(go);
                  handoff.offer(resource);
                  return null;
                });
        Future<?> abandoning =
            pool.submit(
                () -> {
                  await(go);
                  handoff.abandon();
                  return null;
                });
        go.countDown();
        offering.get(10, TimeUnit.SECONDS);
        abandoning.get(10, TimeUnit.SECONDS);

        assertThat(resource.closeCount()).as("round %s：必须恰好关闭一次", round).isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("latch timed out");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting", error);
    }
  }
}
