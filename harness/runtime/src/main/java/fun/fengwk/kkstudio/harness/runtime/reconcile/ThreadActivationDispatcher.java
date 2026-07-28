package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.runtime.execution.StepResult;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Thread activation 的进程内调度边界。
 *
 * <p>它将 {@link ThreadKick} 合并为本 JVM 中有限的 executor task；不持有 durable 工作队列、Thread 状态机或跨节点锁。生产协作链为：
 *
 * <ol>
 *   <li>Core recovery scanner 根据 PostgreSQL runnable/lease 事实调用 {@link #kick(long)}；
 *   <li>本类按 Thread id 合并并发 kick，并把一次 activation 交给 {@link Executor}；
 *   <li>生产 wiring 将 {@link ReconcileRunner} 绑定到 {@link ThreadReconciler#reconcile(long, String)}，由
 *       Reconciler 经事务端口推进 durable facts；
 *   <li>Reconciler 返回 {@link StepResult.Suspended} 时，本类通过 {@link ActivationNotifier} 为其 durable
 *       blocker 对应执行目标发送 best-effort wake hint。
 * </ol>
 *
 * <p>同一 Thread 在本进程内同时最多运行一个 reconcile pass。执行期间的多个 kick 只保留一个 boolean rerun edge；完成时以 {@link
 * ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} 原子决定继续或移除，避免退出窗口丢失新 kick。
 * 跨节点唯一性、lease 与 fencing 始终以数据库 claim 为权威；通知丢失或重复仅影响延迟，由 durable recovery 补偿。
 */
public final class ThreadActivationDispatcher implements ThreadKick {

  /** 单次 activation 的同步 reconcile 入口。 */
  @FunctionalInterface
  public interface ReconcileRunner {
    StepResult reconcile(long threadId, String processorToken);
  }

  private static final System.Logger LOGGER =
      System.getLogger(ThreadActivationDispatcher.class.getName());

  private final ReconcileRunner reconcileRunner;
  private final Executor executor;
  private final ActivationNotifier activationNotifier;

  /** 仅用于进程内按 Thread 合并的 transient 状态，不是 distributed lock 或 durable queue。 */
  private final ConcurrentHashMap<Long, Activation> inflight = new ConcurrentHashMap<>();

  public ThreadActivationDispatcher(
      ReconcileRunner reconcileRunner, Executor executor, ActivationNotifier activationNotifier) {
    this.reconcileRunner = Objects.requireNonNull(reconcileRunner, "reconcileRunner");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.activationNotifier = Objects.requireNonNull(activationNotifier, "activationNotifier");
  }

  /**
   * 请求本地执行一次 activation。
   *
   * <p>第一个 kick 安排 executor task；已有 task 时只记录 rerun。所有在同一个 reconcile pass 期间到达的重复 kick 合并为其后至多一次额外
   * pass；后续 pass 可再独立累积一个 rerun edge。
   */
  @Override
  public void kick(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    Activation fresh = new Activation();
    Activation activation =
        inflight.compute(
            threadId,
            (ignored, current) -> {
              if (current == null) {
                return fresh;
              }
              current.rerun = true;
              return current;
            });
    if (activation != fresh) {
      return;
    }
    try {
      // Reconcile must run outside ConcurrentHashMap.compute so database work never occupies its
      // atomic mapping section.
      executor.execute(() -> run(threadId, fresh));
    } catch (RejectedExecutionException rejection) {
      inflight.remove(threadId, fresh);
      throw rejection;
    } catch (RuntimeException failure) {
      inflight.remove(threadId, fresh);
      throw failure;
    }
  }

  /**
   * 在 map 原子区之外执行一个或多个 reconcile pass。
   *
   * <p>每次 pass 使用新的 processor token 发起数据库 claim；token 随后成为 {@link ThreadOwnership} fencing identity
   * 的组成部分。执行完成后回到同一个 key 的 compute 中消费或保留 rerun edge。
   */
  private void run(long threadId, Activation activation) {
    boolean rerun;
    do {
      try {
        StepResult result = reconcileRunner.reconcile(threadId, UUID.randomUUID().toString());
        if (result instanceof StepResult.Suspended suspended) {
          try {
            activationNotifier.notifyAfterCommit(suspended.continuation().blocker());
          } catch (RuntimeException notificationFailure) {
            LOGGER.log(
                System.Logger.Level.WARNING,
                "thread activation notification failed for " + threadId,
                notificationFailure);
          }
        } else if (result instanceof StepResult.Failed failed) {
          LOGGER.log(
              System.Logger.Level.WARNING,
              "thread reconcile failed for " + threadId + ": " + failed.failure().code());
        }
      } catch (RuntimeException failure) {
        LOGGER.log(
            System.Logger.Level.WARNING, "thread activation crashed for " + threadId, failure);
      }
      rerun =
          inflight.compute(
                  threadId,
                  (ignored, current) -> {
                    if (current != activation) {
                      return current;
                    }
                    if (activation.rerun) {
                      activation.rerun = false;
                      return activation;
                    }
                    return null;
                  })
              == activation;
    } while (rerun);
  }

  /**
   * 单个 map registration 的局部状态。
   *
   * <p>{@code rerun} 是边沿而非计数器：它只记录至少一个尚未消费的 kick，下一 pass 会重新读取 durable snapshot。
   *
   * <p>它只在同一 Thread key 的 {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
   * 中读写，不能在该原子边界之外访问。
   */
  private static final class Activation {
    private boolean rerun;
  }
}
