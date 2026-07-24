package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.kernel.execution.StepResult;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 进程内 Thread activation 合并器。
 *
 * <p>每个 Thread 同时至多运行一次 reconcile。运行期间的 kick 不会丢弃，而是标记一次 rerun；完成时以 map compute 原子地决定继续或移除，避免退出窗口丢
 * edge。数据库 claim 仍是跨节点的唯一权威。
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
  private final ConcurrentHashMap<Long, Activation> inflight = new ConcurrentHashMap<>();

  public ThreadActivationDispatcher(
      ReconcileRunner reconcileRunner, Executor executor, ActivationNotifier activationNotifier) {
    this.reconcileRunner = Objects.requireNonNull(reconcileRunner, "reconcileRunner");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.activationNotifier = Objects.requireNonNull(activationNotifier, "activationNotifier");
  }

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
      executor.execute(() -> run(threadId, fresh));
    } catch (RejectedExecutionException rejection) {
      inflight.remove(threadId, fresh);
      throw rejection;
    } catch (RuntimeException failure) {
      inflight.remove(threadId, fresh);
      throw failure;
    }
  }

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

  private static final class Activation {
    private boolean rerun;
  }
}
