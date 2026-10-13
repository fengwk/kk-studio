package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 一次本地执行的进程内状态：能力句柄、执行 deadline、终态仲裁门与延迟终态窗口。
 *
 * <p>本类是「谁是终态提交者」的唯一归属点：终态抢占与资源上传控制帧出站共享 {@link #terminalGate}，因此终态既不会被之后的控制帧越过，
 * 也不会因能力不配合而丢失或多提交。协调器只驱动它，不另行缓存这些事实。
 */
final class DaemonRunningInvocation {

  private final String invocationId;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean begun = new AtomicBoolean();
  private final AtomicBoolean terminal = new AtomicBoolean();
  private final AtomicReference<EnvironmentCapabilityExecutionHandle> handle =
      new AtomicReference<>();
  private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();

  /** 已发出的收尾请求原因；句柄尚未建立时据此在 {@link #setHandle} 中补发。 */
  private final AtomicReference<EnvironmentCapabilityTerminationCause> terminationCause =
      new AtomicReference<>();

  /**
   * 终态仲裁与资源上传控制帧出站的互斥门。
   *
   * <p>服务端只接受活动调用的上传控制帧，因此「检查调用仍活动 + 发送控制帧」必须与终态抢占互斥：控制帧要么完整发生在终态之前，要么确定不发送；终态绝不会被之后 的控制帧越过。
   *
   * <p>该门同时保护延迟终态窗口的开启与解析：运行时裁决与能力收尾对「谁是终态提交者」只能有一个结论，且窗口必须在请求能力收尾之前开启，否则与收尾竞态的能力终态会因窗口未开启而被丢弃。
   */
  private final Object terminalGate = new Object();

  /** 本次调用的绝对超时时刻；用于让终态编码阶段的上传不超过调用预算。 */
  private volatile long deadlineNanos = Long.MAX_VALUE;

  /** 非空表示延迟终态窗口已开启：终态改由能力收尾或兜底定时器提交。 */
  private DaemonTerminalMessage handoff;

  /** 延迟终态窗口的兜底定时器：到期后由运行时提交 {@link #handoff}。 */
  private volatile ScheduledFuture<?> handoffFallback;

  DaemonRunningInvocation(String invocationId) {
    this.invocationId = invocationId;
  }

  String invocationId() {
    return invocationId;
  }

  long deadlineNanos() {
    return deadlineNanos;
  }

  void setDeadlineNanos(long deadlineNanos) {
    this.deadlineNanos = deadlineNanos;
  }

  boolean isTerminal() {
    return terminal.get();
  }

  /**
   * 尝试原子抢占终态仲裁权（terminal-once 互斥点），并决定终态由谁提交。
   *
   * <p>抢占与资源上传控制帧出站共享 {@link #terminalGate}：终态一旦被抢占，该调用之后的控制帧一律确定不发送；已在临界区内入队的控制帧必然排在终态报文之前。
   *
   * <p>抢占成功且能力声明了收尾预算时，本方法在临界区内开启延迟终态窗口并向能力发出带原因的收尾请求：窗口先于请求开启，因此能力收尾提交的终态一定能接管提交权。窗口到期仍未收尾时由
   * {@code fallback} 提交运行时自己的终态。
   *
   * @param cause 非空表示运行时基于 deadline 或取消裁决，需要请求底层能力收尾；null 表示无需请求能力
   * @param message 运行时自己的终态，同时是延迟终态窗口到期后的兜底终态
   * @param fallback 延迟终态窗口到期后提交兜底终态的动作
   * @return true 表示调用方必须立即提交 {@code message}；false 表示已有终态抢先，或终态已交由延迟窗口提交
   */
  boolean claimTerminal(
      ScheduledExecutorService scheduler,
      EnvironmentCapabilityTerminationCause cause,
      DaemonTerminalMessage message,
      Runnable fallback) {
    boolean deferred;
    synchronized (terminalGate) {
      if (!terminal.compareAndSet(false, true)) {
        return false;
      }
      deferred = openHandoff(scheduler, message, fallback);
    }
    cancelDeadline();
    if (cause != null) {
      terminate(cause);
    }
    return !deferred;
  }

  /**
   * 在终态互斥门内开启延迟终态窗口。
   *
   * <p>只在能力明确声明正预算时开启：普通能力立即由运行时收敛，不引入任何等待。兜底定时器登记失败（scheduler
   * 已停机）时不开启窗口，由运行时立即收敛自己的终态，绝不让终态因调度器不可用而悬挂。
   *
   * @return true 表示窗口已开启，终态提交权已让给能力收尾或兜底定时器
   */
  private boolean openHandoff(
      ScheduledExecutorService scheduler, DaemonTerminalMessage message, Runnable fallback) {
    EnvironmentCapabilityExecutionHandle current = handle.get();
    if (current == null) {
      return false;
    }
    Duration grace = current.terminationGrace();
    if (grace == null || grace.isZero() || grace.isNegative()) {
      return false;
    }
    handoff = message;
    try {
      handoffFallback =
          scheduler.schedule(fallback, Math.max(0L, grace.toMillis()), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException error) {
      handoff = null;
      return false;
    }
    return true;
  }

  /**
   * 能力在收尾预算内提交终态：用能力事实构造的终态接管提交权。
   *
   * <p>只在延迟终态窗口内生效；窗口不存在（或已被兜底定时器收口）时返回 false，调用方按正常终态路径处理。
   *
   * @param capabilityTerminal 以窗口内运行时的裁决终态为输入构造最终终态报文
   * @return true 表示终态已由本方法提交
   */
  boolean resolveHandoff(
      UnaryOperator<DaemonTerminalMessage> capabilityTerminal,
      Consumer<DaemonTerminalMessage> committer) {
    DaemonTerminalMessage resolved;
    synchronized (terminalGate) {
      if (handoff == null) {
        return false;
      }
      resolved = capabilityTerminal.apply(handoff);
      handoff = null;
      ScheduledFuture<?> fallback = handoffFallback;
      handoffFallback = null;
      if (fallback != null) {
        fallback.cancel(false);
      }
    }
    committer.accept(resolved);
    return true;
  }

  /**
   * 在终态互斥门内执行一次资源上传控制帧的出站动作。
   *
   * @param sender 「检查调用仍活动并发送控制帧」的动作；返回 false 表示帧确定未发送
   * @return 传送动作的结果；终态已被抢占时返回 false 且绝不执行动作
   */
  boolean trySendControl(BooleanSupplier sender) {
    synchronized (terminalGate) {
      if (terminal.get()) {
        return false;
      }
      return sender.getAsBoolean();
    }
  }

  boolean begin() {
    return begun.compareAndSet(false, true);
  }

  void setHandle(EnvironmentCapabilityExecutionHandle handle) {
    this.handle.set(handle);
    EnvironmentCapabilityTerminationCause pending = terminationCause.get();
    if (pending != null) {
      handle.terminate(pending);
    }
  }

  void setDeadline(ScheduledFuture<?> deadline) {
    this.deadline.set(deadline);
  }

  /** 请求底层能力以明确原因收尾；原因在句柄尚未建立时被记住，句柄建立后立即补发。 */
  void terminate(EnvironmentCapabilityTerminationCause cause) {
    if (cancelled.compareAndSet(false, true)) {
      terminationCause.set(cause);
      EnvironmentCapabilityExecutionHandle current = handle.get();
      if (current != null) {
        current.terminate(cause);
      }
    }
  }

  private void cancelDeadline() {
    ScheduledFuture<?> current = deadline.getAndSet(null);
    if (current != null) {
      current.cancel(false);
    }
  }
}
