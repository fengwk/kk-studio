package fun.fengwk.kkstudio.harness.runtime.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
              "insertEntry",
              "findEntry",
              "loadEntryPath",
              "insertThread",
              "findThread",
              "lockThread",
              "updateThread",
              "findCommandByClientId",
              "loadQueuedCommands",
              "insertCommands",
              "updateCommands",
              "findModelInvocation",
              "lockModelInvocation",
              "findModelInvocationByTurn",
              "hasModelInvocationForTurn",
              "insertModelInvocation",
              "updateModelInvocation",
              "findToolInvocation",
              "lockToolInvocation",
              "loadToolInvocationsByAssistantEntryId",
              "lockToolInvocationsByAssistantEntryId",
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
              "rescheduleWork"));

  @Test
  void storeRootExposesOnlyTheTransactionEntryPoint() {
    List<String> methodNames =
        Arrays.stream(HarnessStore.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toList());
    assertEquals(List.of("transaction"), methodNames);
    Method transaction = HarnessStore.class.getDeclaredMethods()[0];
    assertEquals(1, transaction.getParameterCount());
    assertEquals(Function.class, transaction.getParameterTypes()[0]);
  }

  @Test
  void transactionHandleExposesExactlyTheTypedPersistencePrimitives() {
    Set<String> actual =
        Arrays.stream(HarnessStore.Transaction.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertEquals(ALLOWED_PRIMITIVES, actual);
  }

  @Test
  void allReadsReturnOptionalOrImmutableListOrEntryPath() {
    for (Method method : HarnessStore.Transaction.class.getDeclaredMethods()) {
      Class<?> returnType = method.getReturnType();
      if (returnType == void.class
          || returnType == long.class
          || returnType == boolean.class
          || returnType == UUID.class) {
        continue;
      }
      assertTrue(
          Optional.class.isAssignableFrom(returnType)
              || List.class.isAssignableFrom(returnType)
              || EntryPath.class.isAssignableFrom(returnType),
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
