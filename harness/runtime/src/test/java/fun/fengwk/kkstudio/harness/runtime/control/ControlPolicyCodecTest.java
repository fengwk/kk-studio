package fun.fengwk.kkstudio.harness.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link ControlPolicyCodec} 必须在输入为非 object、缺少字段、非字符串字段、未知枚举值等情况下明确报错，
 * 同时正确忽略 maxTurns 等无关字段并允许两种合法枚举值。
 */
public class ControlPolicyCodecTest {

  @Test
  public void defaultsWhenFieldsAreAbsent() {
    ControlPolicy policy = ControlPolicyCodec.decode("{}");
    assertEquals(ControlPolicy.DEFAULT, policy);
  }

  @Test
  public void defaultsWhenFieldsAreExplicitlyNull() {
    ControlPolicy policy = ControlPolicyCodec.decode("{\"steeringMode\":null,\"followUpMode\":null}");
    assertEquals(ControlPolicy.DEFAULT, policy);
  }

  @Test
  public void acceptsAllForBothKinds() {
    ControlPolicy policy =
        ControlPolicyCodec.decode("{\"steeringMode\":\"ALL\",\"followUpMode\":\"ALL\"}");
    assertEquals(ControlConsumptionMode.ALL, policy.steeringMode());
    assertEquals(ControlConsumptionMode.ALL, policy.followUpMode());
  }

  @Test
  public void ignoresUnknownTopLevelFields() {
    ControlPolicy policy =
        ControlPolicyCodec.decode(
            "{\"maxTurns\":8,\"maxDepth\":3,\"steeringMode\":\"ONE_AT_A_TIME\"}");
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, policy.steeringMode());
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, policy.followUpMode());
  }

  @Test
  public void rejectsNonObjectRoot() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> ControlPolicyCodec.decode("[]"));
    assertEquals("execution policy must be a JSON object", exception.getMessage());
  }

  @Test
  public void rejectsScalarRoot() {
    assertThrows(IllegalArgumentException.class, () -> ControlPolicyCodec.decode("42"));
  }

  @Test
  public void rejectsNullJson() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> ControlPolicyCodec.decode(null));
    assertEquals("execution policy must not be null", exception.getMessage());
  }

  @Test
  public void rejectsMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> ControlPolicyCodec.decode("{"));
  }

  @Test
  public void rejectsNonStringMode() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> ControlPolicyCodec.decode("{\"steeringMode\":1}"));
    assertEquals(
        "execution policy.steeringMode must be a string when present", exception.getMessage());
  }

  @Test
  public void rejectsObjectMode() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> ControlPolicyCodec.decode("{\"followUpMode\":{}}"));
    assertEquals(
        "execution policy.followUpMode must be a string when present", exception.getMessage());
  }

  @Test
  public void rejectsUnknownEnumValue() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> ControlPolicyCodec.decode("{\"steeringMode\":\"WHENEVER\"}"));
    assertEquals(
        "execution policy.steeringMode must be one of ONE_AT_A_TIME/ALL but was: WHENEVER",
        exception.getMessage());
  }

  @Test
  public void propagatesCauseOnUnknownEnum() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> ControlPolicyCodec.decode("{\"followUpMode\":\"UNKNOWN\"}"));
    assertSame(IllegalArgumentException.class, exception.getCause().getClass());
  }
}