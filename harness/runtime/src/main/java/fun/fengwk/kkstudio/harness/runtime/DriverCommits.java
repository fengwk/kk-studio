package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.UUID;

/**
 * 把已打开的 store transaction 适配为 Driver 回调使用的 {@link DriverCommit}。
 *
 * <p>只转发 id 分配、事务时钟与 Entry 插入；不引入新事务、不改变提交边界。
 */
public final class DriverCommits {

  private DriverCommits() {}

  public static DriverCommit of(HarnessStore.Transaction tx) {
    return new DriverCommit() {
      @Override
      public UUID nextId() {
        return tx.nextId();
      }

      @Override
      public Instant now() {
        return tx.now();
      }

      @Override
      public void insertEntry(Entry entry) {
        tx.entries().insert(entry);
      }
    };
  }
}
