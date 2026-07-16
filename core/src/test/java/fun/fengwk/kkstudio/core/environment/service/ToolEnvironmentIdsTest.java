package fun.fengwk.kkstudio.core.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Strict unsigned positive decimal ID parser contract. */
class ToolEnvironmentIdsTest {

  @Test
  void acceptsUnsignedPositiveDecimal() {
    assertEquals(1L, ToolEnvironmentIds.parsePositive("1", "id"));
    assertEquals(42L, ToolEnvironmentIds.parsePositive("42", "id"));
    assertEquals(
        Long.MAX_VALUE, ToolEnvironmentIds.parsePositive(String.valueOf(Long.MAX_VALUE), "id"));
  }

  @Test
  void rejectsNullAndBlank() {
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive(null, "id"));
    assertThrows(IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("", "id"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("   ", "id"));
  }

  @Test
  void rejectsSignAndLeadingZero() {
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("+1", "id"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("-1", "id"));
    assertThrows(IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("0", "id"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("01", "id"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("+0", "id"));
  }

  @Test
  void rejectsNonDecimal() {
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("abc", "id"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("1.5", "id"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolEnvironmentIds.parsePositive("0x10", "id"));
  }

  @Test
  void rejectsOverflow() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolEnvironmentIds.parsePositive("99999999999999999999", "id"));
  }

  @Test
  void formatReturnsUnsignedDecimal() {
    assertEquals("42", ToolEnvironmentIds.format(42L));
    assertEquals("1", ToolEnvironmentIds.format(1L));
  }
}
