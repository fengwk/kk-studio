package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 已终态完成、取消无副作用的执行句柄单例：同步路径没有任何在途工作，静止立即成立。 */
public enum CompletedToolExecutionHandle implements ToolExecutionHandle {
  INSTANCE;

  private static final CompletionStage<Void> QUIESCENT = CompletableFuture.completedFuture(null);

  @Override
  public void cancel() {}

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public CompletionStage<Void> quiescence() {
    return QUIESCENT;
  }
}
