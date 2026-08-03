package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** TurnSettings is the only durable turn configuration reference and contains names only. */
class TurnSettingsTest {

  @Test
  void acceptsCanonicalNames() {
    TurnSettings settings = new TurnSettings("agent", true);

    assertEquals("agent", settings.agentName());
    assertEquals(true, settings.yoloEnabled());
  }

  @Test
  void rejectsBlankOrNonCanonicalNames() {
    assertThrows(NullPointerException.class, () -> new TurnSettings(null, false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings(" ", false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings(" agent", false));
    assertThrows(IllegalArgumentException.class, () -> new TurnSettings("\tagent", false));
  }
}
