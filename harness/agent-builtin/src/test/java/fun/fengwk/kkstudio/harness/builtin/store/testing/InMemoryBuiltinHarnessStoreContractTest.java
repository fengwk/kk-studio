package fun.fengwk.kkstudio.harness.builtin.store.testing;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 内存 Store 的契约回归：group cooldown 使用 typed 身份并共享核心快照，model invocation 在语义校验前按原顺序检查行锁。 */
class InMemoryBuiltinHarnessStoreContractTest {

  private InMemoryBuiltinHarnessStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryBuiltinHarnessStore();
  }

  @Test
  void groupCooldownKeyDistinguishesFieldTuplesThatWouldCollideWhenConcatenated() {
    store.setNow(T0);
    String groupName = "group";
    long groupVersion = 1L;
    String firstMember = "model:2";
    String firstVariant = "v";
    String secondMember = "model";
    String secondVariant = "2:v";

    // 旧字符串键都会拼成 group:1:model:2:v；typed key 下 freeze A 不得让 B 冷却。
    freeze(groupName, groupVersion, firstMember, firstVariant, T1);
    assertTrue(cooling(groupName, groupVersion, firstMember, firstVariant));
    assertFalse(cooling(groupName, groupVersion, secondMember, secondVariant));

    freeze(groupName, groupVersion, secondMember, secondVariant, T2);
    store.setNow(T1.plusMillis(500));
    assertFalse(cooling(groupName, groupVersion, firstMember, firstVariant));
    assertTrue(cooling(groupName, groupVersion, secondMember, secondVariant));

    // 两者各自延长到独立 deadline，互不覆盖。
    freeze(groupName, groupVersion, firstMember, firstVariant, T3);
    freeze(groupName, groupVersion, secondMember, secondVariant, T4);
    assertTrue(cooling(groupName, groupVersion, firstMember, firstVariant));
    assertTrue(cooling(groupName, groupVersion, secondMember, secondVariant));

    store.setNow(T3.plusMillis(500));
    assertFalse(cooling(groupName, groupVersion, firstMember, firstVariant));
    assertTrue(cooling(groupName, groupVersion, secondMember, secondVariant));
  }

  @Test
  void groupCooldownChangesRollBackWithTheSharedCoreSnapshot() {
    store.setNow(T0);
    String groupName = "group";
    long groupVersion = 7L;
    freeze(groupName, groupVersion, "model-a", "v1", T1);
    freeze(groupName, groupVersion, "model-b", "v1", T2);

    RuntimeException failure = new IllegalStateException("rollback");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    tx -> {
                      builtin(store, tx)
                          .freezeGroupMember(groupName, groupVersion, "model-a", "v1", T4);
                      builtin(store, tx)
                          .freezeGroupMember(groupName, groupVersion, "model-b", "v1", T5);
                      throw failure;
                    })));

    // 回滚后保留原 deadline：T2 之后两者都已过期，而不是被失败事务延长到 T4 / T5。
    store.setNow(T2.plusMillis(500));
    assertFalse(cooling(groupName, groupVersion, "model-a", "v1"));
    assertFalse(cooling(groupName, groupVersion, "model-b", "v1"));
  }

  @Test
  void insertModelInvocationChecksThreadLockBeforeTurnStart() {
    TurnBaseline baseline = seedTurnBaseline(store);
    ModelInvocation invocation =
        modelInvocation(
            TestIds.id(1),
            baseline.threadId(),
            TestIds.id(999),
            baseline.turnStartEntryId(),
            ModelInvocationStatus.READY,
            null,
            T1);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      builtin(store, tx).insertModelInvocation(invocation);
                      return null;
                    }));

    assertEquals(notLocked("THREAD", "thread:" + baseline.threadId()), failure.getMessage());
  }

  @Test
  void updateModelInvocationChecksModelLockBeforeExistence() {
    TurnBaseline baseline = seedTurnBaseline(store);
    UUID missingModelId = TestIds.id(999);
    ModelInvocation invocation =
        modelInvocation(
            missingModelId,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.turnStartEntryId(),
            ModelInvocationStatus.READY,
            null,
            T1);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      builtin(store, tx).updateModelInvocation(invocation);
                      return null;
                    }));

    assertEquals(notLocked("DRIVER", "driver:" + missingModelId), failure.getMessage());
  }

  @Test
  void deleteModelInvocationChecksModelLockBeforeChildScan() {
    TurnBaseline baseline = seedTurnBaseline(store);
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload("call-1"));
    UUID modelId = TestIds.id(1);
    inTransaction(
        store,
        tx -> {
          tx.threads().lock(baseline.threadId());
          builtin(store, tx)
              .insertModelInvocation(
                  modelInvocation(
                      modelId,
                      baseline.threadId(),
                      baseline.turnStartEntryId(),
                      baseline.turnStartEntryId(),
                      ModelInvocationStatus.READY,
                      null,
                      T1));
        });
    inTransaction(
        store,
        tx ->
            tx.tools()
                .insert(
                    List.of(
                        toolInvocation(
                            TestIds.id(10),
                            baseline.threadId(),
                            baseline.turnStartEntryId(),
                            assistantEntryId,
                            0,
                            "call-1",
                            ToolInvocationStatus.READY,
                            T2))));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> store.transaction(tx -> builtin(store, tx).deleteModelInvocation(modelId)));

    assertEquals(notLocked("DRIVER", "driver:" + modelId), failure.getMessage());
  }

  @Test
  void insertModelInvocationDuplicateIdKeepsCanonicalMessage() {
    TurnBaseline baseline = seedTurnBaseline(store);
    ModelInvocation invocation =
        modelInvocation(
            TestIds.id(1),
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.turnStartEntryId(),
            ModelInvocationStatus.READY,
            null,
            T1);

    inTransaction(
        store,
        tx -> {
          tx.threads().lock(baseline.threadId());
          builtin(store, tx).insertModelInvocation(invocation);
          IllegalArgumentException failure =
              assertThrows(
                  IllegalArgumentException.class,
                  () -> builtin(store, tx).insertModelInvocation(invocation));
          assertEquals("duplicate model invocation id " + invocation.id(), failure.getMessage());
        });
  }

  @Test
  void modelInvocationTimestampPrecisionUsesCoreMessage() {
    TurnBaseline baseline = seedTurnBaseline(store);
    ModelInvocation canonical =
        modelInvocation(
            TestIds.id(1),
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.turnStartEntryId(),
            ModelInvocationStatus.READY,
            null,
            T1);
    ModelInvocation nonCanonical =
        new ModelInvocation(
            canonical.id(),
            canonical.threadId(),
            canonical.turnStartEntryId(),
            canonical.requestHeadEntryId(),
            canonical.requestSpec(),
            canonical.status(),
            canonical.attempt(),
            canonical.streamCheckpoint(),
            canonical.result(),
            canonical.error(),
            canonical.resultEntryId(),
            canonical.failedAttempts(),
            canonical.createdAt().plusNanos(1),
            canonical.updatedAt().plusNanos(1));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.transaction(
                    tx -> {
                      builtin(store, tx).insertModelInvocation(nonCanonical);
                      return null;
                    }));

    assertEquals("durable timestamps must use millisecond precision", failure.getMessage());
  }

  private void freeze(
      String groupName,
      long groupVersion,
      String memberModelName,
      String memberVariant,
      Instant cooldownUntil) {
    inTransaction(
        store,
        tx ->
            builtin(store, tx)
                .freezeGroupMember(
                    groupName, groupVersion, memberModelName, memberVariant, cooldownUntil));
  }

  private boolean cooling(
      String groupName, long groupVersion, String memberModelName, String memberVariant) {
    return store.transaction(
        tx ->
            builtin(store, tx)
                .isGroupMemberCooling(groupName, groupVersion, memberModelName, memberVariant));
  }

  private static String notLocked(String rank, String key) {
    return "LockKey[rank="
        + rank
        + ", key="
        + key
        + ", ownerThreadId=null] is not locked in this transaction";
  }
}
