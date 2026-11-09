package fun.fengwk.kkstudio.harness.spi.execution;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;

/**
 * 一次本地执行的物理生命周期句柄：由 {@link LocalExecutionLifecycle#begin} 在 PREPARED 已持久化后返回。
 *
 * <p>句柄只在物理 body 与本地资源都结束后才允许写出退出证明；取消请求、失权与 supersede 都不释放句柄。所有结束方法幂等，且只有第一次结束调用 生效。
 */
public interface LocalExecutionHandle {

  /**
   * 数据库登记的精确事实；未登记、登记结果不明或已对账确认数据库不存在该事实时为 {@code null}。
   *
   * <p>{@link #registered()} 为 {@code false} 时不得进入 body：调用方必须先释放已取得资源，再以 {@link
   * ExecutionExitState#NOT_STARTED} 结束句柄；已登记但确定未进入 body 的代次也用同一方式结束。
   */
  ExecutionExitFact fact();

  /** 是否已确认数据库登记提交成功；只有 {@code true} 才允许进入 body。 */
  boolean registered();

  /** 登记当前物理 body 线程，使 {@link #requestCancel()} 能精确打断它；body 退出时必须再次解除。 */
  void attachBody(Thread body);

  /** 解除物理 body 线程；重复解除幂等。 */
  void detachBody();

  /** 请求取消：只打断已登记的本地 body，不写任何持久退出证据，也不从物理集合移除。 */
  void requestCancel();

  /**
   * body 与本地资源都已结束后写出退出证明：EXIT_PROOF 落盘、数据库确认退出并完成 Thread/Join 结算、删除日志。
   *
   * <p>{@link ExecutionExitState#STUDIO_EXITED} 要求已登记执行体与资源均已结束；{@link
   * ExecutionExitState#NOT_STARTED} 表示确定没有进入 body（已登记代次确认未启动，未登记代次清理 PREPARED）。
   */
  void provenExit(ExecutionExitState state);

  /**
   * 无法证明退出：本地资源关闭失败或 body 仍未结束。
   *
   * <p>保留 PREPARED 与未确认事实，并要求日志目录锁保留到 JVM 退出；不得把清理失败当成退出。
   */
  void exitNotProven();
}
