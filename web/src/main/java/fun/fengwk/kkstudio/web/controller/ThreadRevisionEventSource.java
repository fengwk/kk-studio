package fun.fengwk.kkstudio.web.controller;

import java.util.UUID;
import java.util.function.Consumer;

/** 面向 SSE 的最小 revision 通知边界。 */
@FunctionalInterface
interface ThreadRevisionEventSource {
  record Event(String revision, boolean resync) {
    public Event {
      if (resync) {
        if (revision != null) {
          throw new IllegalArgumentException("resync event must not carry a revision");
        }
      } else if (revision == null || !revision.matches("0|[1-9]\\d*")) {
        throw new IllegalArgumentException(
            "revision event must carry a canonical non-negative decimal revision");
      } else {
        try {
          Long.parseLong(revision);
        } catch (NumberFormatException error) {
          throw new IllegalArgumentException("revision event exceeds bigint range", error);
        }
      }
    }
  }

  AutoCloseable subscribe(UUID threadId, long afterRevision, Consumer<Event> consumer);
}
