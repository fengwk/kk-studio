package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** StreamCheckpoint 的 attempt/sequence/content 不变式。 */
class StreamCheckpointTest {

  @Test
  void normalizesNullableTextAndThinkingWithoutTrimmingVisibleContent() {
    StreamCheckpoint textOnly = new StreamCheckpoint(1, 0L, "partial text", "");
    assertEquals("partial text", textOnly.text());
    assertEquals("", textOnly.thinking());
    assertEquals(0L, textOnly.sequence());

    StreamCheckpoint thinkingOnly = new StreamCheckpoint(2, 7L, "", "partial thinking");
    assertEquals("", thinkingOnly.text());
    assertEquals("partial thinking", thinkingOnly.thinking());
    assertEquals(7L, thinkingOnly.sequence());

    StreamCheckpoint both = new StreamCheckpoint(3, 1L, "text", "thinking");
    assertEquals("text", both.text());
    assertEquals("thinking", both.thinking());

    StreamCheckpoint whitespace = new StreamCheckpoint(4, 2L, " ", "");
    assertEquals(" ", whitespace.text());
    assertEquals("", whitespace.thinking());
  }

  @Test
  void rejectsInvalidAttemptSequenceAndAllEmptyContent() {
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(0, 0L, "t", ""));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(-1, 0L, "t", ""));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, -1L, "t", ""));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, "", ""));
    assertThrows(IllegalArgumentException.class, () -> new StreamCheckpoint(1, 0L, null, null));
  }
}
