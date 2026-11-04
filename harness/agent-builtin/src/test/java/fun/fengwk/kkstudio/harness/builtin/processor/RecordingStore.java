package fun.fengwk.kkstudio.harness.builtin.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreCallProbe.wrap;

import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Model 测试共用的 Store 记录夹具：委托唯一的内存 Builtin Store，并同时提供事务 / 行锁 / UPDATE 计数、锁序记录与失败注入。
 *
 * <p>只统计 UPDATE 次数不足以证明「每个 buffered delta 零数据库开销」：流式测试必须同时证明零事务、零行锁查询；生命周期测试则要核对 Thread / Model /
 * Work 的加锁顺序。因此两套测试共用本夹具，而不是各自维护一层同构代理。
 */
final class RecordingStore implements HarnessStore, BuiltinStore {

  private final InMemoryBuiltinHarnessStore delegate;
  private final List<List<String>> transactions = new CopyOnWriteArrayList<>();
  private final AtomicInteger modelInvocationUpdateCount = new AtomicInteger();
  private final AtomicInteger transactionCount = new AtomicInteger();
  private final AtomicInteger modelInvocationLockCount = new AtomicInteger();
  private final AtomicInteger claimedWorkLockCount = new AtomicInteger();
  private final AtomicReference<String> threadRecord;

  volatile RuntimeException updateInvocationFailure;
  volatile RuntimeException transactionFailure;

  RecordingStore(InMemoryBuiltinHarnessStore delegate) {
    this(delegate, null);
  }

  RecordingStore(InMemoryBuiltinHarnessStore delegate, AtomicReference<String> threadRecord) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.threadRecord = threadRecord;
  }

  InMemoryBuiltinHarnessStore delegate() {
    return delegate;
  }

  int modelInvocationUpdateCount() {
    return modelInvocationUpdateCount.get();
  }

  int transactionCount() {
    return transactionCount.get();
  }

  int modelInvocationLockCount() {
    return modelInvocationLockCount.get();
  }

  int claimedWorkLockCount() {
    return claimedWorkLockCount.get();
  }

  void resetCounters() {
    modelInvocationUpdateCount.set(0);
    transactionCount.set(0);
    modelInvocationLockCount.set(0);
    claimedWorkLockCount.set(0);
    transactions.clear();
  }

  /** 仅保留包含 Thread 行锁的事务，按提交顺序返回其写 / 锁操作。 */
  List<List<String>> threadLockTransactions() {
    return transactions.stream()
        .filter(ops -> ops.stream().anyMatch(op -> op.startsWith("threads.lock:")))
        .toList();
  }

  @Override
  public BuiltinStore.Transaction in(HarnessStore.Transaction tx) {
    return delegate.in(tx);
  }

  @Override
  public void afterCommit(Runnable action) {
    delegate.afterCommit(action);
  }

  @Override
  public void assertNoAmbientTransaction() {
    delegate.assertNoAmbientTransaction();
  }

  @Override
  public <T> T transaction(Function<HarnessStore.Transaction, T> callback) {
    if (transactionFailure != null) {
      throw transactionFailure;
    }
    transactionCount.incrementAndGet();
    return delegate.transaction(
        tx -> {
          List<String> ops = new CopyOnWriteArrayList<>();
          transactions.add(ops);
          HarnessStore.Transaction recording =
              wrap(
                  tx,
                  (call, next) -> {
                    String name = call.operation();
                    switch (name) {
                      case "updateModelInvocation" -> {
                        if (threadRecord != null) {
                          threadRecord.set(Thread.currentThread().getName());
                        }
                        modelInvocationUpdateCount.incrementAndGet();
                        if (updateInvocationFailure != null) {
                          throw updateInvocationFailure;
                        }
                      }
                      case "lockModelInvocation" -> modelInvocationLockCount.incrementAndGet();
                      case "work.lockClaimed" -> claimedWorkLockCount.incrementAndGet();
                      default -> {
                        // 其余操作只参与锁序记录。
                      }
                    }
                    if (isRecordedOperation(name)) {
                      ops.add(
                          call.arguments() == null || call.arguments().length == 0
                              ? name
                              : name + ":" + call.argument(0));
                    }
                    return next.invoke();
                  });
          return callback.apply(recording);
        });
  }

  private static boolean isRecordedOperation(String name) {
    return name.equals("threads.lock")
        || name.equals("threads.lockTree")
        || name.equals("threads.findAncestorChain")
        || name.equals("sessions.lockForKeyShare")
        || name.equals("sessions.lockForUpdate")
        || name.equals("tools.lock")
        || name.equals("tools.lockByAssistantEntry")
        || name.equals("work.lock")
        || name.equals("work.lockClaimed")
        || name.equals("work.request")
        || name.equals("work.complete")
        || name.equals("work.reschedule")
        || name.equals("threads.update")
        || name.equals("tools.update")
        || name.equals("lockModelInvocation")
        || name.startsWith("update");
  }
}
