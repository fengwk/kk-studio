package fun.fengwk.kkstudio.agent.tool.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.tool.NoopToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;

import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ManagedToolExecutionHandle 的资源绑定与取消测试。
 *
 * @author fengwk
 */
public class ManagedToolExecutionHandleTest {

  /** 校验先 cancel 后绑定资源时，future、delegate 和 hook 都会被补偿取消。 */
  @Test
  public void testCancelBeforeBindingCancelsFutureDelegateAndHook() {
    ManagedToolExecutionHandle handle = new ManagedToolExecutionHandle();
    AtomicInteger hookCounter = new AtomicInteger();
    FutureTask<Void> future = new FutureTask<>(() -> null);
    NoopToolExecutionHandle delegate = new NoopToolExecutionHandle();
    handle.setCancelHook(hookCounter::incrementAndGet);

    handle.cancel();
    handle.bindFuture(future);
    handle.bindDelegate(delegate);

    assertTrue(handle.isCancelled());
    assertTrue(future.isCancelled());
    assertTrue(delegate.isCancelled());
    assertEquals(1, hookCounter.get());
  }

  /** 校验重复绑定 future 或 delegate 会拒绝并取消多余资源。 */
  @Test
  public void testDuplicateBindingRejected() {
    ManagedToolExecutionHandle handle = new ManagedToolExecutionHandle();
    FutureTask<Void> future1 = new FutureTask<>(() -> null);
    FutureTask<Void> future2 = new FutureTask<>(() -> null);
    ToolExecutionHandle delegate1 = new NoopToolExecutionHandle();
    ToolExecutionHandle delegate2 = new NoopToolExecutionHandle();

    handle.bindFuture(future1);
    handle.bindDelegate(delegate1);

    assertThrows(IllegalStateException.class, () -> handle.bindFuture(future2));
    assertThrows(IllegalStateException.class, () -> handle.bindDelegate(delegate2));
    assertTrue(future2.isCancelled());
    assertTrue(delegate2.isCancelled());
  }

  /** 校验已绑定资源后 cancel 会取消 future、delegate 并运行 hook。 */
  @Test
  public void testCancelAfterBindingCancelsFutureDelegateAndHook() {
    ManagedToolExecutionHandle handle = new ManagedToolExecutionHandle();
    AtomicInteger hookCounter = new AtomicInteger();
    FutureTask<Void> future = new FutureTask<>(() -> null);
    NoopToolExecutionHandle delegate = new NoopToolExecutionHandle();
    handle.setCancelHook(hookCounter::incrementAndGet);
    handle.bindFuture(future);
    handle.bindDelegate(delegate);

    handle.cancel();

    assertTrue(handle.isCancelled());
    assertTrue(future.isCancelled());
    assertTrue(delegate.isCancelled());
    assertEquals(1, hookCounter.get());
  }

  /** 校验下游 cancel 或 hook 抛异常不会向上泄漏。 */
  @Test
  public void testCancelSwallowsDelegateAndHookExceptions() {
    ManagedToolExecutionHandle handle = new ManagedToolExecutionHandle();
    handle.setCancelHook(
        () -> {
          throw new IllegalStateException("hook");
        });
    handle.bindDelegate(
        new ToolExecutionHandle() {
          @Override
          public void cancel() {
            throw new IllegalStateException("delegate");
          }

          @Override
          public boolean isCancelled() {
            return false;
          }
        });

    assertDoesNotThrow(handle::cancel);
    assertTrue(handle.isCancelled());
  }

  /** 校验 null 绑定与重复 cancel 的边界行为。 */
  @Test
  public void testNullBindingsAndRepeatedCancel() {
    ManagedToolExecutionHandle handle = new ManagedToolExecutionHandle();

    assertDoesNotThrow(() -> handle.bindFuture(null));
    assertThrows(IllegalArgumentException.class, () -> handle.bindDelegate(null));
    assertDoesNotThrow(handle::cancel);
    assertDoesNotThrow(handle::cancel);
    assertTrue(handle.isCancelled());
  }
}
