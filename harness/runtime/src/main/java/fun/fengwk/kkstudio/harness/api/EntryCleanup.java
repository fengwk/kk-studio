package fun.fengwk.kkstudio.harness.api;

import fun.fengwk.kkstudio.harness.api.history.Entry;

import java.util.List;
import java.util.UUID;

/** Cleans product-owned state associated with entries removed from a session. */
@FunctionalInterface
public interface EntryCleanup {
  void accept(UUID sessionId, List<Entry> removed, List<Entry> retained);
}
