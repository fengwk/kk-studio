package fun.fengwk.kkstudio.web.controller;

import java.util.function.Consumer;

/** Minimal SSE-facing revision notification boundary. */
@FunctionalInterface
interface ThreadRevisionEventSource {
  record Event(String revision, boolean resync) {}

  AutoCloseable subscribe(long threadId, Consumer<Event> consumer);
}
