package fun.fengwk.kkstudio.harness.environment.transport;

import org.eclipse.jetty.util.Callback;

import java.util.concurrent.CompletableFuture;

/** 测试专用的 Jetty Util Callback 桥接器。 */
class TestUtilCallback implements Callback {

  private final CompletableFuture<Void> future = new CompletableFuture<>();

  CompletableFuture<Void> future() {
    return future;
  }

  @Override
  public void succeeded() {
    future.complete(null);
  }

  @Override
  public void failed(Throwable x) {
    future.completeExceptionally(x);
  }
}
