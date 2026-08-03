package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** StreamCheckpoint attempt/sequence/content invariants. */
class StreamCheckpointTest {

  @Test
  void acceptsNullableTextOrThinkingWithContent() {
    StreamCheckpoint textOnly = new StreamCheckpoint(1, 0L, "partial text", null);
    assertEquals("partial text", textOnly.text());
    assertNull(textOnly.thinking());
    assertEquals(0L, textOnly.sequence());

    StreamCheckpoint thinkingOnly = new StreamCheckpoint(2, 7L, null, "partial thinking");
    assertNull(thinkingOnly.text());
    assertEquals("partial thinking", thinkingOnly.thinking());
    assertEquals(7L, thinkingOnly.sequence());

    StreamCheckpoint both = new StreamCheckpoint(3, 1L, "text", "thinking");
    assertEquals("text", both.text());
    assertEquals("thinking", both.thinking());
  }

  @Test
  void rejectsInvalidAttemptSequenceAndAllEmptyContent() {
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(0, 0L, "t", null));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(-1, 0L, "t", null));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, -1L, "t", null));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, null, null));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, "", null));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, null, ""));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, " ", ""));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, "", " "));
  }
}
