package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.concurrent.CompletionStage;

/** 一次工具执行的取消句柄。 */
public interface ToolExecutionHandle {

  /** 请求取消；调用必须幂等。 */
  void cancel();

  /** 是否已经请求取消。 */
  boolean isCancelled();

  /**
   * 该调用不再使用本地 Tool 所拥有的资源的完成事实。
   *
   * <p>取消只是通知，绝不代表执行线程已经退出：调用方只有在 {@code quiescence()} 完成后才能释放该工具仍可能访问的本地资源（例如被本次调用
   * 钉住的插件实例）。同步终结的调用直接返回已完成的 stage；异步调用必须由真实的工作线程退出事实完成，不能用 {@link
   * java.util.concurrent.Future#cancel(boolean)} 或 {@link java.util.concurrent.FutureTask#done()}
   * 冒充。
   *
   * <p>实现必须返回非 null 的 stage；不提供任何默认实现，因为「已完成」与「仍在运行」是相反的资源事实，绝不能静默当作静止。
   */
  CompletionStage<Void> quiescence();
}
