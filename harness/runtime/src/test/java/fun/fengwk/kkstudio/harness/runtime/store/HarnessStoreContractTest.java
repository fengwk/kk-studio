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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Guards the HarnessStore contract surface: exactly one transaction root, exactly the typed
 * persistence primitives, and no Repository / Specification / generic save / business use-case
 * methods or framework classes in the store package.
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
      if (returnType == void.class || returnType == long.class || returnType == boolean.class) {
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
