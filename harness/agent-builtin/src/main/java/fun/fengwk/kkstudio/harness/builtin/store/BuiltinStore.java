package fun.fengwk.kkstudio.harness.builtin.store;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin Driver 的私有持久化端口。
 *
 * <p>不是通用 UnitOfWork：{@link #in(HarnessStore.Transaction)} 只把调用方已开启的 Runtime 事务投影为窄 typed 视图。生产实现与
 * Runtime transaction 共享同一连接、数据库时间、锁序跟踪与 rollback-only 状态；视图自身绝不开启事务。
 *
 * <p>模型状态 CRUD 只存在于本接口；Runtime 不导入本类型。
 */
public interface BuiltinStore {

  /** 取得当前 Runtime 事务上的 Builtin typed 视图；transaction 必须由同一 Store 的运行时产生。 */
  Transaction in(HarnessStore.Transaction tx);

  interface Transaction extends HarnessStore.Transaction {

    Optional<ModelInvocation> findModelInvocation(UUID id);

    Optional<ModelInvocation> lockModelInvocation(UUID id);

    Optional<ModelInvocation> findModelInvocationByTurn(UUID threadId, UUID turnStartEntryId);

    void insertModelInvocation(ModelInvocation invocation);

    void updateModelInvocation(ModelInvocation invocation);

    boolean isGroupMemberCooling(
        String groupName, long groupVersion, String memberModelName, String memberVariant);

    void freezeGroupMember(
        String groupName,
        long groupVersion,
        String memberModelName,
        String memberVariant,
        Instant cooldownUntil);

    boolean deleteModelInvocation(UUID modelInvocationId);
  }
}
