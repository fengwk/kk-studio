package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PostgreSQL verifies SYSTEM context reuse, deltas, and branch path inheritance. */
class PostgresqlSystemContextTest {

  @Test
  void reusesBaselineAppendsEnvironmentChangeOnceAndSharesItWithBranchThread() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    Baseline baseline = seedThreadBaseline(store);
    SystemContext firstEnvironment = new SystemContext(sections("env-a"), List.of(), List.of());

    EntryPath beforeFirstRequest = path(store, baseline.threadId());
    assertTrue(beforeFirstRequest.requiresSystemBaseline());
    UUID baselineEntryId = appendSystemEntry(store, baseline, baseline(firstEnvironment));
    EntryPath afterFirstRequest = path(store, baseline.threadId());
    assertEquals(1, systemEntries(afterFirstRequest));

    // The next request with unchanged context reuses the stored baseline without appending SYSTEM.
    assertTrue(
        afterFirstRequest.systemContext().orElseThrow().changesTo(firstEnvironment).isEmpty());
    assertEquals(baselineEntryId, afterFirstRequest.head().id());
    assertEquals(1, systemEntries(path(store, baseline.threadId())));

    SystemContext secondEnvironment = new SystemContext(sections("env-b"), List.of(), List.of());
    SystemPayload environmentChange =
        afterFirstRequest.systemContext().orElseThrow().changesTo(secondEnvironment).orElseThrow();
    assertFalse(environmentChange.baseline());
    assertEquals(
        Map.of("environment", sections("env-b").get("environment")), environmentChange.sections());
    assertTrue(environmentChange.removedSections().isEmpty());
    assertTrue(environmentChange.addedTools().isEmpty());
    assertTrue(environmentChange.removedTools().isEmpty());

    UUID changeEntryId = appendSystemEntry(store, baseline, environmentChange);
    EntryPath afterEnvironmentSwitch = path(store, baseline.threadId());
    assertEquals(2, systemEntries(afterEnvironmentSwitch));
    assertEquals(secondEnvironment, afterEnvironmentSwitch.systemContext().orElseThrow());
    assertTrue(
        afterEnvironmentSwitch
            .systemContext()
            .orElseThrow()
            .changesTo(secondEnvironment)
            .isEmpty());
    assertEquals(2, systemEntries(path(store, baseline.threadId())));

    UUID branchThreadId = insertBranchThread(store, baseline, changeEntryId);
    EntryPath branchPath = path(store, branchThreadId);
    assertEquals(2, systemEntries(branchPath));
    assertEquals(secondEnvironment, branchPath.systemContext().orElseThrow());
  }

  private static SystemPayload baseline(SystemContext context) {
    return new SystemPayload(context.sections(), List.of(), List.of(), true);
  }

  private static Map<String, String> sections(String environment) {
    Map<String, String> sections = new LinkedHashMap<>();
    sections.put("agent", "[agent] Use the configured assistant.");
    sections.put("environment", "[environment] Current environment: " + environment);
    return sections;
  }

  private static UUID appendSystemEntry(
      HarnessStore store, Baseline baseline, SystemPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          UUID id = tx.nextId();
          Instant now = tx.now();
          tx.entries()
              .insert(new Entry(id, baseline.sessionId(), thread.headEntryId(), payload, now));
          tx.threads().update(thread.advanceHead(id, now));
          return id;
        });
  }

  private static UUID insertBranchThread(HarnessStore store, Baseline baseline, UUID headEntryId) {
    return store.transaction(
        tx -> {
          UUID id = tx.nextId();
          Instant now = tx.now();
          tx.threads()
              .insert(
                  new ThreadState(
                      id,
                      baseline.sessionId(),
                      null,
                      headEntryId,
                      "a".repeat(64),
                      "branch",
                      id,
                      false,
                      ThreadKind.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0,
                      1,
                      0,
                      now,
                      now));
          return id;
        });
  }

  private static EntryPath path(HarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> tx.entries().loadPath(tx.threads().find(threadId).orElseThrow().headEntryId()));
  }

  private static int systemEntries(EntryPath path) {
    return (int)
        path.entries().stream().filter(entry -> entry.payload() instanceof SystemPayload).count();
  }
}
