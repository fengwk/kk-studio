package fun.fengwk.kkstudio.harness.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ControlPolicy#modeFor} 与构造器非空校验。 */
public class ControlPolicyTest {

  @Test
  public void modeForReturnsSteeringOrFollowUp() {
    ControlPolicy policy = new ControlPolicy(ControlConsumptionMode.ALL, ControlConsumptionMode.ALL);
    assertEquals(ControlConsumptionMode.ALL, policy.modeFor(RunControlKind.STEER));
    assertEquals(ControlConsumptionMode.ALL, policy.modeFor(RunControlKind.FOLLOW_UP));
  }

  @Test
  public void modeForDifferentiatesKinds() {
    ControlPolicy policy =
        new ControlPolicy(ControlConsumptionMode.ONE_AT_A_TIME, ControlConsumptionMode.ALL);
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, policy.modeFor(RunControlKind.STEER));
    assertEquals(ControlConsumptionMode.ALL, policy.modeFor(RunControlKind.FOLLOW_UP));
  }

  @Test
  public void rejectsNullModes() {
    assertThrows(
        NullPointerException.class,
        () -> new ControlPolicy(null, ControlConsumptionMode.ONE_AT_A_TIME));
    assertThrows(
        NullPointerException.class,
        () -> new ControlPolicy(ControlConsumptionMode.ONE_AT_A_TIME, null));
  }

  @Test
  public void rejectsNullKind() {
    assertThrows(NullPointerException.class, () -> ControlPolicy.DEFAULT.modeFor(null));
  }
}