package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** TurnSettings is the only durable turn configuration reference and contains names only. */
class TurnSettingsTest {

  @Test
  void acceptsCanonicalNamesAndNullableEnvironment() {
    TurnSettings settings = new TurnSettings("agent", null, true);

    assertEquals("agent", settings.agentName());
    assertEquals(null, settings.environmentName());
    assertEquals(true, settings.yoloEnabled());
  }

  @Test
  void rejectsBlankOrNonCanonicalNames() {
    assertThrows(NullPointerException.class, () -> new TurnSettings(null, null, false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings(" ", null, false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings(" agent", null, false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings("agent", " env", false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings("\tagent", null, false));
    assertThrows(
        IllegalArgumentException.class, () -> new TurnSettings("agent", "\u2003env", false));
  }
}
