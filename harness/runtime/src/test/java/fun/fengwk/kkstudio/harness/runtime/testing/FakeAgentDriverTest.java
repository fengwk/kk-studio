package fun.fengwk.kkstudio.harness.runtime.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.runtime.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;

/** {@link FakeAgentDriver} 的可配置中立投影与默认 no-op 边界。 */
class FakeAgentDriverTest {

  @Test
  void defaultsRemainInactiveAndAllowNullResolution() {
    FakeAgentDriver driver = new FakeAgentDriver();

    assertNull(driver.resolveTurn(null, null, null, null));
    assertEquals(DriverActivity.NONE, driver.readState(null, null, null).activity());
    assertTrue(driver.stop(null, null, null, null, null, id(1)).executionIds().isEmpty());
    assertTrue(driver.allowsQueuedInputHarvest(null, null, null));
  }

  @Test
  void exposesConfiguredStateAppliedFactsAndStopResult() {
    TurnResolution resolution =
        new TurnResolution.Rejected(new AssistantError("FAILED", "provider failed"));
    DriverApplied applied =
        new DriverApplied(id(2), null, false, true, true, TurnEndOutcome.COMPLETED, List.of());
    DriverStopResult stop = new DriverStopResult(id(3), List.of(id(4)));
    FakeAgentDriver.FakeLockedDriverState state =
        new FakeAgentDriver.FakeLockedDriverState(
            DriverActivity.ACTIVE, id(5), null, null, new WorkTarget(WorkTargetType.MODEL, id(4)));

    FakeAgentDriver driver =
        new FakeAgentDriver(() -> resolution)
            .withState(state)
            .withApplied(applied)
            .withStopResult(stop);

    assertSame(resolution, driver.resolveTurn(null, null, null, null));
    assertSame(state, driver.lockState(null, null, null));
    assertSame(applied, driver.applyResponse(null, null, null, state, null));
    assertSame(stop, driver.stop(null, null, null, state, null, id(6)));
    assertEquals(DriverActivity.ACTIVE, state.activity());
    assertEquals(new WorkTarget(WorkTargetType.MODEL, id(4)), state.workTarget());
    assertFalse(state.cancelsToolBatch());
    assertTrue(driver.allowsQueuedInputHarvest(null, null, null));

    driver.completeStop(null, null, null);
    assertEquals(1, driver.completeStopInvocations());

    driver.withAllowsQueuedInputHarvest(false);
    assertFalse(driver.allowsQueuedInputHarvest(null, null, null));

    FakeAgentDriver.FakeLockedDriverState cancellingState =
        new FakeAgentDriver.FakeLockedDriverState(
            DriverActivity.RESPONSE_PENDING,
            id(5),
            null,
            null,
            new WorkTarget(WorkTargetType.MODEL, id(4)),
            true);
    assertTrue(cancellingState.cancelsToolBatch());
  }
}
