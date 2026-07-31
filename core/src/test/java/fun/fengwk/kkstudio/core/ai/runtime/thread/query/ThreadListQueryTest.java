package fun.fengwk.kkstudio.core.ai.runtime.thread.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Opaque Thread cursor validation coverage at the HTTP-independent query boundary. */
class ThreadListQueryTest {

  @Test
  void defaultsAndRoundTripsCreatedCursor() {
    // Default parsing covers the public recent/20 contract; round-trip proves cursor precision and
    // sort identity survive the opaque wire representation.
    ThreadListQuery defaults = ThreadListQuery.parse(null, null, null);
    assertEquals(ThreadSort.RECENT, defaults.sort());
    assertEquals(ThreadListQuery.DEFAULT_LIMIT, defaults.limit());
    assertNull(defaults.cursor());

    ThreadCursor cursor =
        new ThreadCursor(ThreadSort.CREATED, Instant.parse("2026-07-31T12:34:56.789Z"), 42L);
    ThreadListQuery decoded = ThreadListQuery.parse("created", ThreadCursorCodec.encode(cursor), 7);
    assertEquals(ThreadSort.CREATED, decoded.sort());
    assertEquals(cursor, decoded.cursor());
    assertEquals(7, decoded.limit());
  }

  @Test
  void rejectsMalformedOrMismatchedCursorAndInvalidBounds() {
    // These failures guard keyset correctness: a cursor cannot be reused under another ordering,
    // and invalid limits/ids cannot reach SQL.
    String recentCursor =
        ThreadCursorCodec.encode(
            new ThreadCursor(ThreadSort.RECENT, Instant.parse("2026-07-31T00:00:00Z"), 1L));
    assertThrows(
        IllegalArgumentException.class, () -> ThreadListQuery.parse("created", recentCursor, 20));
    assertThrows(
        IllegalArgumentException.class, () -> ThreadListQuery.parse("recent", "not-a-cursor", 20));
    assertThrows(IllegalArgumentException.class, () -> ThreadListQuery.parse("other", null, 20));
    assertThrows(IllegalArgumentException.class, () -> ThreadListQuery.parse("recent", null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadListQuery.parse("recent", null, ThreadListQuery.MAX_LIMIT + 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCursor(ThreadSort.RECENT, Instant.EPOCH, 0L));
  }
}
