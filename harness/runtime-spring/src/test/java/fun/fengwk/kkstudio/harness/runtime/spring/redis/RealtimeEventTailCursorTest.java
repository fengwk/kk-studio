package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail.Record;

import java.time.Duration;
import java.util.List;

/** RealtimeEventTail 的 initial cursor、cursor 归一化与 Record 契约。 */
class RealtimeEventTailCursorTest {

  private static final RealtimeEventTail TAIL =
      new RealtimeEventTail() {
        @Override
        public String initialCursor(long threadId) {
          return "123-0";
        }

        @Override
        public List<Record> readAfter(long threadId, String afterId, int count, Duration block) {
          return List.of();
        }
      };

  @Test
  void initialCursorIsAConcreteStableId() {
    assertEquals("123-0", TAIL.initialCursor(1L));
  }

  @Test
  void legacyCursorsNormalizeToRetainedWindowStart() {
    assertEquals("0-0", RealtimeEventTail.normalizeAfterId(null));
    assertEquals("0-0", RealtimeEventTail.normalizeAfterId(""));
    assertEquals("0-0", RealtimeEventTail.normalizeAfterId("0"));
  }

  @Test
  void transientLiveEdgeOffsetIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("$"));
  }

  @Test
  void canonicalStreamCursorIsPreserved() {
    assertEquals("123-0", RealtimeEventTail.normalizeAfterId("123-0"));
    assertEquals("1750000000000-42", RealtimeEventTail.normalizeAfterId("1750000000000-42"));
  }

  @Test
  void malformedCursorsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("abc"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("1"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("1-"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("-1"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("1-2-3"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId(" 1-2"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("1.5-2"));
  }

  @Test
  void recordExposesIdAndPayload() {
    Record record = new Record("1-0", "{}");
    assertEquals("1-0", record.id());
    assertEquals("{}", record.payloadJson());
  }

  @Test
  void recordRejectsNullParts() {
    assertThrows(NullPointerException.class, () -> new Record(null, "{}"));
    assertThrows(NullPointerException.class, () -> new Record("1-0", null));
  }
}
