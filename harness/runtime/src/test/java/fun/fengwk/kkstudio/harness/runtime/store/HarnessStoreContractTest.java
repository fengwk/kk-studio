package fun.fengwk.kkstudio.harness.runtime.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.lang.reflect.Method;
import java.util.Arrays;
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
              "countIncompleteSubagentJoins",
              "insertJoin",
              "findJoin",
              "loadIncompleteJoins",
              "loadPendingDeliveries",
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
              "loadCommandsByThread",
              "loadCancelledCommandsByRequest",
              "loadQueuedCommands",
              "insertCommands",
              "updateCommands",
              "findModelInvocation",
              "lockModelInvocation",
              "findModelInvocationByTurn",
              "insertModelInvocation",
              "updateModelInvocation",
              "findToolInvocation",
              "lockToolInvocation",
              "loadToolInvocationsByAssistantEntryId",
              "lockToolInvocationsByAssistantEntryId",
              "listPendingToolInvocations",
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
              "deleteModelInvocation",
              "deleteThreads",
              "deleteEntries",
              "deleteSession"));

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
  }

  /** 测试意图：读取原语只能返回 Optional / 不可变 List / EntryPath / BranchSettings 这四类只读投影。 */
  @Test
  void allReadsReturnOptionalOrImmutableListOrEntryPathOrBranchSettings() {
    for (Method method : HarnessStore.Transaction.class.getDeclaredMethods()) {
      if (method.isSynthetic()) {
        continue;
      }
      Class<?> returnType = method.getReturnType();
      if (returnType == void.class
          || returnType == long.class
          || returnType == int.class
          || returnType == boolean.class
          || returnType == int.class
          || returnType == UUID.class) {
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
