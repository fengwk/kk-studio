package fun.fengwk.kkstudio.core.ai.runtime.thread.query;

import java.time.Instant;
import java.util.Objects;

/** Decoded opaque keyset cursor for one Thread sort mode. */
public record ThreadCursor(ThreadSort sort, Instant time, long threadId) {

  public ThreadCursor {
    Objects.requireNonNull(sort, "sort");
    Objects.requireNonNull(time, "time");
    if (threadId <= 0) {
      throw new IllegalArgumentException("cursor thread id must be positive");
    }
  }
}
