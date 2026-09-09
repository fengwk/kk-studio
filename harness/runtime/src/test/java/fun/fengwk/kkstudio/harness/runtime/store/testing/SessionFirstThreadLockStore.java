package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 测试代理：每个事务内锁定既有 Thread 前，必须已锁定其父 Session。
 *
 * <p>用于确定性守卫会追加 Session FK 子行的运行路径，避免测试依赖 PostgreSQL 真实死锁的竞态时序。
 */
public final class SessionFirstThreadLockStore {

  private SessionFirstThreadLockStore() {}

  public static HarnessStore wrap(HarnessStore delegate) {
    Objects.requireNonNull(delegate, "delegate");
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (storeProxy, storeMethod, storeArguments) -> {
              if (!storeMethod.getName().equals("transaction")) {
                return invoke(storeMethod, delegate, storeArguments);
              }
              @SuppressWarnings("unchecked")
              Function<HarnessStore.Transaction, ?> callback =
                  (Function<HarnessStore.Transaction, ?>) storeArguments[0];
              return delegate.transaction(
                  transaction -> callback.apply(wrapTransaction(transaction)));
            });
  }

  private static HarnessStore.Transaction wrapTransaction(HarnessStore.Transaction transaction) {
    Set<UUID> lockedSessions = new HashSet<>();
    return (HarnessStore.Transaction)
        Proxy.newProxyInstance(
            HarnessStore.Transaction.class.getClassLoader(),
            new Class<?>[] {HarnessStore.Transaction.class},
            (transactionProxy, method, arguments) -> {
              if (method.getName().equals("lockSessionForKeyShare")
                  || method.getName().equals("lockSessionForUpdate")) {
                Object result = invoke(method, transaction, arguments);
                if (result instanceof Optional<?> optional && optional.isPresent()) {
                  lockedSessions.add((UUID) arguments[0]);
                }
                return result;
              }
              if (method.getName().equals("lockThread")) {
                UUID threadId = (UUID) arguments[0];
                ThreadState thread = transaction.findThread(threadId).orElse(null);
                if (thread != null && !lockedSessions.contains(thread.sessionId())) {
                  throw new AssertionError(
                      "thread " + threadId + " was locked before session " + thread.sessionId());
                }
              }
              return invoke(method, transaction, arguments);
            });
  }

  private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }
}
