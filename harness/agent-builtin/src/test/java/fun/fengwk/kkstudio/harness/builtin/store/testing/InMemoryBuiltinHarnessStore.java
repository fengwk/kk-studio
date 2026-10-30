package fun.fengwk.kkstudio.harness.builtin.store.testing;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessTransaction;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin 测试 Store：组合 runtime 的唯一通用内存 Store，把 ModelInvocation 与 group cooldown 放进核心事务快照。
 *
 * <p>本类只做两件事：继承 runtime 内存 Store / 事务核心，并在自己的事务子类上暴露 {@link BuiltinStore.Transaction}。Model 行与属性
 * 存放在 runtime 核心事务快照的 driver row 区域，因此与核心 Store 共享同一 copy-on-write 快照、rollback、driver 级锁与按 Thread
 * 深删除语义；它不是生产实现，也不是生产 SPI。
 *
 * <p>Builtin-specific 的模型校验全部在 {@link InMemoryBuiltinModelStore}，核心 Store 不实现这些约束，只提供 driver row
 * 快照与 owner 关联。
 */
public final class InMemoryBuiltinHarnessStore extends InMemoryHarnessStore
    implements BuiltinStore {

  public InMemoryBuiltinHarnessStore() {
    super();
  }

  /** 播种全局 id 序列，供事务/回滚测试使用。 */
  public InMemoryBuiltinHarnessStore(long initialNextId) {
    super(initialNextId);
  }

  @Override
  protected InMemoryHarnessTransaction createTransaction(InMemoryState state) {
    return new BuiltinTransaction(state);
  }

  @Override
  public BuiltinStore.Transaction in(HarnessStore.Transaction tx) {
    Objects.requireNonNull(tx, "tx");
    if (tx instanceof BuiltinStore.Transaction builtin) {
      return builtin;
    }
    throw new IllegalArgumentException("transaction does not expose BuiltinStore.Transaction");
  }

  private final class BuiltinTransaction extends InMemoryHarnessTransaction
      implements BuiltinStore.Transaction {

    private final InMemoryBuiltinModelStore models;

    private BuiltinTransaction(InMemoryState state) {
      super(InMemoryBuiltinHarnessStore.this, state);
      this.models = new InMemoryBuiltinModelStore(this);
    }

    @Override
    public Optional<ModelInvocation> findModelInvocation(UUID id) {
      return models.find(id);
    }

    @Override
    public Optional<ModelInvocation> lockModelInvocation(UUID id) {
      return models.lock(id);
    }

    @Override
    public Optional<ModelInvocation> findModelInvocationByTurn(
        UUID threadId, UUID turnStartEntryId) {
      return models.findByTurn(threadId, turnStartEntryId);
    }

    @Override
    public void insertModelInvocation(ModelInvocation invocation) {
      models.insert(invocation);
    }

    @Override
    public void updateModelInvocation(ModelInvocation invocation) {
      models.update(invocation);
    }

    @Override
    public boolean isGroupMemberCooling(
        String groupName, long groupVersion, String memberModelName, String memberVariant) {
      return models.isCooling(groupName, groupVersion, memberModelName, memberVariant);
    }

    @Override
    public void freezeGroupMember(
        String groupName,
        long groupVersion,
        String memberModelName,
        String memberVariant,
        Instant cooldownUntil) {
      models.freeze(groupName, groupVersion, memberModelName, memberVariant, cooldownUntil);
    }

    @Override
    public boolean deleteModelInvocation(UUID modelInvocationId) {
      return models.delete(modelInvocationId);
    }
  }

  /** 仅供测试断言使用：当前已提交的 model 行。 */
  public List<ModelInvocation> committedModelInvocations() {
    return super.transaction(tx -> ((BuiltinTransaction) tx).models.rows());
  }
}
