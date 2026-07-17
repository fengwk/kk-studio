package fun.fengwk.kkstudio.core.harness.observability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ObservabilityLimitsTest {

  /** Native EventSource reconnects take the max of query and Last-Event-ID cursors. */
  @Test
  void resolveResumeCursorUsesMaximumValidDecimal() {
    assertEquals(0L, ObservabilityLimits.resolveResumeCursor(null, null, "afterSequence"));
    assertEquals(0L, ObservabilityLimits.resolveResumeCursor("", "  ", "afterSequence"));
    assertEquals(7L, ObservabilityLimits.resolveResumeCursor("7", "3", "afterSequence"));
    assertEquals(9L, ObservabilityLimits.resolveResumeCursor("0", "9", "afterSequence"));
    assertEquals(
        9007199254740993L,
        ObservabilityLimits.resolveResumeCursor("1", "9007199254740993", "afterEventId"));
  }

  /** Invalid or negative cursor values remain hard request errors. */
  @Test
  void resolveResumeCursorRejectsInvalidValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ObservabilityLimits.resolveResumeCursor("-1", "0", "afterSequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ObservabilityLimits.resolveResumeCursor("1", "not-a-number", "afterEventId"));
  }
}
