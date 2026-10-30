package fun.fengwk.kkstudio.harness.builtin.runtime.store.testing;

import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试用的事务调用探针。
 *
 * <p>把真实聚合视图包成接口代理，调用方按实际聚合路径（例如 {@code threads.lock}、{@code work.lockClaimed}）
 * 记录调用、覆盖返回值或注入故障。直接位于事务接口上的 Builtin 方法保留真实方法名。
 */
public final class StoreCallProbe {

  private StoreCallProbe() {}

  /** 一次探针拦截到的调用；{@code operation} 是真实聚合路径或事务方法名。 */
  public record Call(String operation, Method method, Object[] arguments) {

    public Object argument(int index) {
      return arguments == null ? null : arguments[index];
    }
  }

  /** 探针回调；{@code next} 执行真实调用。 */
  @FunctionalInterface
  public interface Handler {
    Object handle(Call call, Next next) throws Throwable;
  }

  /** 真实调用闭包。 */
  @FunctionalInterface
  public interface Next {
    Object invoke() throws Throwable;
  }

  public static HarnessStore.Transaction wrap(HarnessStore.Transaction tx, Handler handler) {
    Map<String, Object> aggregates = new HashMap<>();
    Class<?>[] interfaces =
        tx instanceof BuiltinStore.Transaction
            ? new Class<?>[] {BuiltinStore.Transaction.class}
            : new Class<?>[] {HarnessStore.Transaction.class};
    return (HarnessStore.Transaction)
        Proxy.newProxyInstance(
            HarnessStore.Transaction.class.getClassLoader(),
            interfaces,
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return invoke(tx, method, args);
              }
              Class<?> type = method.getReturnType();
              if (type.isInterface()) {
                String aggregate = method.getName();
                return aggregates.computeIfAbsent(
                    aggregate,
                    ignored ->
                        aggregateProxy(
                            aggregate, type, readAggregate(tx, aggregate, method), handler));
              }
              return handler.handle(
                  new Call(method.getName(), method, args), () -> invoke(tx, method, args));
            });
  }

  private static Object aggregateProxy(
      String aggregate, Class<?> type, Object target, Handler handler) {
    return Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[] {type},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return invoke(target, method, args);
          }
          return handler.handle(
              new Call(aggregate + "." + method.getName(), method, args),
              () -> invoke(target, method, args));
        });
  }

  private static Object readAggregate(
      HarnessStore.Transaction tx, String aggregate, Method accessor) {
    try {
      return invoke(tx, accessor, null);
    } catch (RuntimeException | Error error) {
      throw error;
    } catch (Throwable error) {
      throw new IllegalStateException("cannot read aggregate " + aggregate, error);
    }
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }
}
