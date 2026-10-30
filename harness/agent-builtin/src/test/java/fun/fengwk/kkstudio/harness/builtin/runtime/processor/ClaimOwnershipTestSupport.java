package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 在指定工作线程首次 ownership 事务结束后暂停，不持有 Store 或准入锁。 */
public final class ClaimOwnershipTestSupport {
  private ClaimOwnershipTestSupport() {}

  public static HarnessStore gate(
      HarnessStore store,
      AtomicReference<Thread> worker,
      CountDownLatch checked,
      CountDownLatch resume) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class, BuiltinStore.class},
            (proxy, method, args) -> {
              Object result;
              try {
                result = method.invoke(store, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
              if (method.getName().equals("transaction")
                  && Thread.currentThread() == worker.get()
                  && checked.getCount() != 0) {
                checked.countDown();
                if (!resume.await(5, TimeUnit.SECONDS)) {
                  throw new AssertionError("ownership gate timed out");
                }
              }
              return result;
            });
  }
}
