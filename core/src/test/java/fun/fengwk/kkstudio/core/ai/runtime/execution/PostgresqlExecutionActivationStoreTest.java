package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.util.List;
import java.util.Set;

class PostgresqlExecutionActivationStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00.123456Z");

  @Test
  void rejectsNonPositiveDueScanLimit() {
    PostgresqlExecutionActivationStore store =
        new PostgresqlExecutionActivationStore(mock(ExecutionActivationMapper.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> store.findEligibleDue(ExecutionActivationEnvironmentEligibility.empty(), NOW, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.findEligibleDue(ExecutionActivationEnvironmentEligibility.empty(), NOW, -1));
  }

  @Test
  void mapsNullAndEmptyEligibilityToAnEmptyEnvironmentArray() {
    ExecutionActivationMapper mapper = mock(ExecutionActivationMapper.class);
    PostgresqlExecutionActivationStore store = new PostgresqlExecutionActivationStore(mapper);
    when(mapper.findEligibleDue(any(), any(), eq(1))).thenReturn(List.of());
    when(mapper.findNearestEligibleWakeAt(any())).thenReturn(null);

    assertTrue(store.findEligibleDue(null, NOW, 1).isEmpty());
    assertTrue(store.findEligibleDue(() -> null, NOW, 1).isEmpty());
    assertTrue(store.findEligibleDue(() -> Set.of(), NOW, 1).isEmpty());
    assertTrue(store.findNearestEligibleWakeAt(null).isEmpty());
    assertTrue(store.findNearestEligibleWakeAt(() -> null).isEmpty());

    verify(mapper, times(3)).findEligibleDue(any(), any(), eq(1));
    verify(mapper, times(2)).findNearestEligibleWakeAt(any());
  }

  @Test
  void copiesNonEmptyEligibilityIntoMapperArguments() {
    ExecutionActivationMapper mapper = mock(ExecutionActivationMapper.class);
    PostgresqlExecutionActivationStore store = new PostgresqlExecutionActivationStore(mapper);
    when(mapper.findEligibleDue(any(), any(), eq(1))).thenReturn(List.of());

    assertTrue(
        store
            .findEligibleDue(ExecutionActivationEnvironmentEligibility.of(Set.of("env-a")), NOW, 1)
            .isEmpty());

    ArgumentCaptor<String[]> environmentNames = ArgumentCaptor.forClass(String[].class);
    verify(mapper).findEligibleDue(any(), environmentNames.capture(), eq(1));
    assertArrayEquals(new String[] {"env-a"}, environmentNames.getValue());
  }

  @Test
  void rejectsInvalidIdentity() {
    PostgresqlExecutionActivationStore store =
        new PostgresqlExecutionActivationStore(mock(ExecutionActivationMapper.class));

    assertThrows(NullPointerException.class, () -> store.deleteIfExists(null, 1L));
    assertThrows(
        IllegalArgumentException.class, () -> store.deleteIfExists(ExecutionTargetKind.THREAD, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.deleteIfExists(ExecutionTargetKind.THREAD, -1L));
  }

  @Test
  void rejectsLockedMutationWhenTheRowIsMissing() {
    ExecutionActivationMapper mapper = mock(ExecutionActivationMapper.class);
    PostgresqlExecutionActivationStore store = new PostgresqlExecutionActivationStore(mapper);
    when(mapper.lockForUpdate("TOOL_INVOCATION", 1L)).thenReturn(null);
    boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 1L, NOW));
      assertThrows(
          IllegalStateException.class,
          () -> store.activateLocked(ExecutionTargetKind.TOOL_INVOCATION, 1L, NOW));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previous);
    }
  }

  @Test
  void rejectsLockedOperationsWithoutAnActiveTransaction() {
    PostgresqlExecutionActivationStore store =
        new PostgresqlExecutionActivationStore(mock(ExecutionActivationMapper.class));
    boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      assertThrows(IllegalStateException.class, () -> store.lock(ExecutionTargetKind.THREAD, 1L));
      assertThrows(
          IllegalStateException.class, () -> store.lockDue(ExecutionTargetKind.THREAD, 1L, NOW));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previous);
    }
  }

  @Test
  void constructorRejectsNullMapper() {
    assertThrows(NullPointerException.class, () -> new PostgresqlExecutionActivationStore(null));
  }
}
