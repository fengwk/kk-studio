package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class ExecutionActivationEnvironmentEligibilityTest {

  @Test
  void emptyReturnsNoEnvironmentNames() {
    assertEquals(
        Set.of(), ExecutionActivationEnvironmentEligibility.empty().readyEnvironmentNames());
  }

  @Test
  void ofNullReturnsAnEmptySnapshot() {
    assertEquals(
        Set.of(), ExecutionActivationEnvironmentEligibility.of(null).readyEnvironmentNames());
  }

  @Test
  void ofCopiesNamesIntoAnUnmodifiableSnapshot() {
    Set<String> names = new HashSet<>(Set.of("env-a"));

    Set<String> snapshot =
        ExecutionActivationEnvironmentEligibility.of(names).readyEnvironmentNames();

    assertEquals(Set.of("env-a"), snapshot);
    names.add("env-b");
    assertEquals(Set.of("env-a"), snapshot);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add("env-c"));
  }
}
