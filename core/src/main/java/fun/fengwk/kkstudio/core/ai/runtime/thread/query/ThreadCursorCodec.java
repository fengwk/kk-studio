package fun.fengwk.kkstudio.core.ai.runtime.thread.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Encodes and validates opaque versioned keyset cursors. */
public final class ThreadCursorCodec {

  private static final String VERSION = "v1";

  private ThreadCursorCodec() {}

  public static String encode(ThreadCursor cursor) {
    String value =
        String.join(
            "|",
            VERSION,
            cursor.sort().wireValue(),
            Long.toString(cursor.time().toEpochMilli()),
            Long.toString(cursor.threadId()));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  public static ThreadCursor decode(String raw, ThreadSort expectedSort) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      String value = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
      String[] parts = value.split("\\|", -1);
      if (parts.length != 4 || !VERSION.equals(parts[0])) {
        throw new IllegalArgumentException("cursor has an unsupported format");
      }
      ThreadSort sort = ThreadSort.parse(parts[1]);
      if (sort != expectedSort) {
        throw new IllegalArgumentException("cursor sort does not match sort");
      }
      long epochMillis = Long.parseLong(parts[2]);
      long threadId = Long.parseLong(parts[3]);
      return new ThreadCursor(sort, Instant.ofEpochMilli(epochMillis), threadId);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("cursor is invalid", error);
    }
  }
}
