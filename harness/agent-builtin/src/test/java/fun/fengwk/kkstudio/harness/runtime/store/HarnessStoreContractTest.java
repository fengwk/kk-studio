package fun.fengwk.kkstudio.harness.runtime.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 守护 HarnessStore 的契约边界：只能有一个 transaction 根方法、只能暴露确定类型 的持久化 primitive，store 包下不允许出现 Repository /
 * Specification / 通用 save / 业务用例 方法或框架类。
 */
class HarnessStoreContractTest {

  /**
   * Builtin 私有窄视图 {@link BuiltinStore.Transaction} 独有的持久化原语：模型状态 CRUD 与 group cooldown。它们不属于
   * Runtime 公共 store 契约，只在 Builtin Driver 的 typed 视图上存在。
   */
  private static final Set<String> BUILTIN_PRIMITIVES =
      Set.copyOf(
          List.of(
              "findModelInvocation",
              "lockModelInvocation",
              "findModelInvocationByTurn",
              "insertModelInvocation",
              "updateModelInvocation",
              "isGroupMemberCooling",
              "freezeGroupMember",
              "deleteModelInvocation"));

  private static final Set<String> ALLOWED_PRIMITIVES =
      Set.copyOf(
          List.of(
              "nextId",
              "insertSession",
              "findSession",
              "updateSession",
              "insertEntry",
              "findEntry",
              "findRootEntry",
              "loadEntryPath",
              "loadContributorCustomEntriesOnPath",
              "loadBranchSettings",
              "loadEntriesBySessionId",
              "insertThread",
              "findThread",
              "lockThread",
              "updateThread",
              "listThreadsBySession",
              "findAncestorChain",
              "lockTree",
              "lockJoinAdmission",
              "listChildren",
              "countIncompleteChildJoins",
              "hasIncompleteOwnerChildJoin",
              "countIncompleteSubagentJoins",
              "insertJoin",
              "findJoin",
              "loadIncompleteJoins",
              "updateJoin",
              "insertStopReceipts",
              "findStopReceipt",
              "loadStopReceiptsByThread",
              "loadStopReceiptsByRootRequest",
              "deleteJoinsByChild",
              "deleteJoinsForThreads",
              "lockSessionForKeyShare",
              "lockSessionForUpdate",
              "findCommandByIdempotencyKey",
              "findCommand",
              "hasAppliedNotificationAfter",
              "loadCancelledCommandsByRequest",
              "loadQueuedCommands",
              "insertCommands",
              "updateCommands",
              "findToolInvocation",
              "lockToolInvocation",
              "loadToolInvocationsByAssistantEntryId",
              "lockToolInvocationsByAssistantEntryId",
              "listPendingToolInvocations",
              "listPendingEnvironmentWaits",
              "findNextEnvironmentWaitChange",
              "listEnvironmentToolWaits",
              "findToolResultEntryByInvocationId",
              "insertToolInvocations",
              "updateToolInvocations",
              "findWork",
              "lockWork",
              "lockClaimedWork",
              "deleteWork",
              "requestWork",
              "claimNextWork",
              "renewWork",
              "completeWork",
              "rescheduleWork",
              "deleteToolInvocationsByIds",
              "deleteThreads",
              "deleteEntries",
              "deleteSession",
              "registerExecution",
              "confirmExecution",
              "abandonExecution",
              "loadUnconfirmedExecutions",
              "findExecution",
              "now",
              "listThreadsByRoot"));

  @Test
  void storeRootExposesOnlyTheTransactionEntryPoint() {
    List<String> methodNames =
        Arrays.stream(HarnessStore.class.getDeclaredMethods())
            .map(Method::getName)
            .sorted()
            .collect(Collectors.toList());
    // 根边界只有 transaction 根方法，外加两个与物理事务边界直接相关的原语：afterCommit（提交后副作用）与
    // assertNoAmbientTransaction（必须离开事务的入口守卫）。
    assertEquals(List.of("afterCommit", "assertNoAmbientTransaction", "transaction"), methodNames);
    Method transaction =
        Arrays.stream(HarnessStore.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("transaction"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, transaction.getParameterCount());
    assertEquals(Function.class, transaction.getParameterTypes()[0]);
  }

  @Test
  void transactionHandleExposesExactlyTheTypedPersistencePrimitives() {
    Set<String> actual =
        Arrays.stream(HarnessStore.Transaction.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertEquals(ALLOWED_PRIMITIVES, actual);
    // 全历史 Command 读取只属于测试辅助，不得以任何名字回到生产 store 契约。
    assertFalse(actual.contains("loadCommandsByThread"), "test-only command history query leaked");
  }

  /**
   * 测试意图：模型状态 CRUD 与 group cooldown 原语只存在于 Builtin 私有窄视图 {@link BuiltinStore.Transaction}；该窄视图必须
   * 精确等于这组原语（既不多也不少），并且与 Runtime 公共 {@link HarnessStore.Transaction} 契约互斥。
   */
  @Test
  void builtinStoreTransactionExposesExactlyTheModelAndGroupCooldownPrimitives() {
    Set<String> actual =
        Arrays.stream(BuiltinStore.Transaction.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertEquals(BUILTIN_PRIMITIVES, actual);
    // 两侧契约互斥：Builtin 私有原语不得以任何名字回到 Runtime 公共 store 契约。
    assertTrue(
        Collections.disjoint(BUILTIN_PRIMITIVES, ALLOWED_PRIMITIVES),
        "builtin-only primitives must not appear in the runtime store contract");
  }

  /** 测试意图：读取原语只能返回 Optional / 不可变 List / EntryPath / BranchSettings 这四类只读投影。 */
  @Test
  void allReadsReturnOptionalOrImmutableListOrEntryPathOrBranchSettings() {
    for (Method method : HarnessStore.Transaction.class.getDeclaredMethods()) {
      if (method.isSynthetic()) {
        continue;
      }
      // 登记是写入原语，返回冻结后的身份；不把它当作额外的读取投影。
      if (method.getName().equals("registerExecution")) {
        assertEquals(ExecutionExitFact.class, method.getReturnType());
        continue;
      }
      Class<?> returnType = method.getReturnType();
      if (returnType == void.class
          || returnType == long.class
          || returnType == int.class
          || returnType == boolean.class
          || returnType == int.class
          || returnType == UUID.class
          || returnType == Instant.class) {
        continue;
      }
      assertTrue(
          Optional.class.isAssignableFrom(returnType)
              || List.class.isAssignableFrom(returnType)
              || EntryPath.class.isAssignableFrom(returnType)
              || BranchSettings.class.isAssignableFrom(returnType),
          "unexpected read return type " + returnType + " on " + method.getName());
    }
  }

  @Test
  void transactionHandleExposesNoForbiddenBusinessMethodsOrGenericSave() {
    Set<String> actual =
        Arrays.stream(HarnessStore.Transaction.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
    for (String forbidden :
        List.of(
            "applyTerminalModel",
            "applyToolBatch",
            "startTurn",
            "stop",
            "approve",
            "decideNextAction",
            "harvestAndInvoke",
            "enqueue",
            "cancel",
            "save")) {
      assertFalse(actual.contains(forbidden), "forbidden business method " + forbidden);
    }
  }
}
