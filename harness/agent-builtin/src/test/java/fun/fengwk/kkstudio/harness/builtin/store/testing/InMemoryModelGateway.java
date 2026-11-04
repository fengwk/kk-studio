package fun.fengwk.kkstudio.harness.builtin.store.testing;

import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessTransaction;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;

/** 测试模型目录与 Harness 共用 copy-on-write 事务快照。 */
public abstract class InMemoryModelGateway implements ModelGateway {
  private record VersionKey(String group) {}

  private record MemberKey(String group, long version, String model, String variant) {}

  private record Cooldown(long version, Instant until) {}

  public void setGroupVersion(HarnessStore.Transaction transaction, String group, long version) {
    memory(transaction).putDriverAttribute(new VersionKey(group), version);
  }

  @Override
  public boolean isGroupMemberCooling(
      HarnessStore.Transaction transaction,
      String group,
      long version,
      String model,
      String variant) {
    InMemoryHarnessTransaction tx = memory(transaction);
    if (!tx.driverAttribute(new VersionKey(group))
        .filter(value -> value.equals(version))
        .isPresent()) {
      return false;
    }
    return tx.driverAttribute(new MemberKey(group, version, model, variant))
        .map(Cooldown.class::cast)
        .filter(value -> value.version() == version && value.until().isAfter(tx.now()))
        .isPresent();
  }

  @Override
  public void freezeGroupMember(
      HarnessStore.Transaction transaction,
      String group,
      long version,
      String model,
      String variant,
      Instant until) {
    InMemoryHarnessTransaction tx = memory(transaction);
    if (!tx.driverAttribute(new VersionKey(group))
        .filter(value -> value.equals(version))
        .isPresent()) {
      return;
    }
    MemberKey key = new MemberKey(group, version, model, variant);
    Cooldown previous = tx.driverAttribute(key).map(Cooldown.class::cast).orElse(null);
    if (previous == null || (previous.version() == version && previous.until().isBefore(until))) {
      tx.putDriverAttribute(key, new Cooldown(version, until));
    }
  }

  private static InMemoryHarnessTransaction memory(HarnessStore.Transaction tx) {
    if (!(tx instanceof InMemoryHarnessTransaction memory)) {
      throw new IllegalArgumentException("expected in-memory transaction");
    }
    memory.now();
    return memory;
  }
}
