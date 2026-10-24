package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试专用 store 包装：只在实际退出确认持久化（{@code confirmExecution}）调用点注入失败。
 *
 * <p>全部读写委托真实内存 store；故障发生在确认事实写入之前。用于验证 processor 的真实 finally 路径会吞掉持久化失败并保留 UNCONFIRMED，而不是依赖日志文本。
 */
final class ExecutionExitFailureStore {

  private ExecutionExitFailureStore() {}

  /**
   * 包装真实 store；{@code failConfirm} 为 true 时每次 {@code confirmExecution} 都抛 {@link
   * InjectedPersistenceFailure}。无论是否注入，都会统计确认尝试次数供主线程断言。
   */
  static HarnessStore wrapping(
      HarnessStore delegate, AtomicInteger confirmAttempts, boolean failConfirm) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (!"transaction".equals(method.getName())) {
                return invoke(delegate, method, args);
              }
              @SuppressWarnings("unchecked")
              Function<HarnessStore.Transaction, ?> callback =
                  (Function<HarnessStore.Transaction, ?>) args[0];
              return delegate.transaction(
                  tx ->
                      callback.apply(
                          (HarnessStore.Transaction)
                              Proxy.newProxyInstance(
                                  HarnessStore.Transaction.class.getClassLoader(),
                                  new Class<?>[] {HarnessStore.Transaction.class},
                                  (txProxy, txMethod, txArgs) -> {
                                    if ("confirmExecution".equals(txMethod.getName())) {
                                      confirmAttempts.incrementAndGet();
                                      if (failConfirm) {
                                        throw new InjectedPersistenceFailure(
                                            "injected exit confirmation persistence failure");
                                      }
                                    }
                                    return invoke(tx, txMethod, txArgs);
                                  })));
            });
  }

  private static Object invoke(Object target, Method method, Object[] args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 明确的确认持久化失败，便于调试时从 warn 之外的事实断言定位注入点。 */
  static final class InjectedPersistenceFailure extends IllegalStateException {
    InjectedPersistenceFailure(String message) {
      super(message);
    }
  }
}
