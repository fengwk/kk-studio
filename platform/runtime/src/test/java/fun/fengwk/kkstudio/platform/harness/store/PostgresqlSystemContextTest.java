package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL verifies SYSTEM context reuse, deltas, and branch path inheritance. */
class PostgresqlSystemContextTest {

  @Test
  void environmentSwitchNoneToAAndBAppendsOnlySectionChanges() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    Baseline baseline = seedThreadBaseline(store);
    List<ProviderToolDefinition> tools = List.of(definition("read_file", "Read a file"));
    SystemContext noEnvironment = new SystemContext(sections("none"), tools, List.of());

    EntryPath beforeFirstRequest = path(store, baseline.threadId());
    assertTrue(beforeFirstRequest.requiresSystemBaseline());
    UUID baselineEntryId = appendSystemEntry(store, baseline, baseline(noEnvironment));
    EntryPath afterFirstRequest = path(store, baseline.threadId());
    assertEquals(1, systemEntries(afterFirstRequest));

    // The next request with unchanged context reuses the stored baseline without appending SYSTEM.
    assertTrue(afterFirstRequest.systemContext().orElseThrow().changesTo(noEnvironment).isEmpty());
    assertEquals(baselineEntryId, afterFirstRequest.head().id());
    assertEquals(1, systemEntries(path(store, baseline.threadId())));

    SystemContext environmentA = new SystemContext(sections("env-a"), tools, List.of());
    SystemPayload firstEnvironmentChange =
        afterFirstRequest.systemContext().orElseThrow().changesTo(environmentA).orElseThrow();
    assertFalse(firstEnvironmentChange.baseline());
    assertEquals(
        Map.of("environment", sections("env-a").get("environment")),
        firstEnvironmentChange.sections());
    assertTrue(firstEnvironmentChange.removedSections().isEmpty());
    assertTrue(firstEnvironmentChange.addedTools().isEmpty());
    assertTrue(firstEnvironmentChange.removedTools().isEmpty());

    appendSystemEntry(store, baseline, firstEnvironmentChange);
    EntryPath afterFirstEnvironment = path(store, baseline.threadId());
    assertEquals(2, systemEntries(afterFirstEnvironment));
    assertEquals(environmentA, afterFirstEnvironment.systemContext().orElseThrow());

    SystemContext environmentB = new SystemContext(sections("env-b"), tools, List.of());
    SystemPayload secondEnvironmentChange =
        afterFirstEnvironment.systemContext().orElseThrow().changesTo(environmentB).orElseThrow();
    assertEquals(
        Map.of("environment", sections("env-b").get("environment")),
        secondEnvironmentChange.sections());
    assertTrue(secondEnvironmentChange.removedSections().isEmpty());
    assertTrue(secondEnvironmentChange.addedTools().isEmpty());
    assertTrue(secondEnvironmentChange.removedTools().isEmpty());

    UUID secondChangeEntryId = appendSystemEntry(store, baseline, secondEnvironmentChange);
    EntryPath afterSecondEnvironment = path(store, baseline.threadId());
    assertEquals(3, systemEntries(afterSecondEnvironment));
    assertEquals(environmentB, afterSecondEnvironment.systemContext().orElseThrow());
    assertTrue(
        afterSecondEnvironment.systemContext().orElseThrow().changesTo(environmentB).isEmpty());

    UUID branchThreadId = insertBranchThread(store, baseline, secondChangeEntryId);
    EntryPath branchPath = path(store, branchThreadId);
    assertEquals(3, systemEntries(branchPath));
    assertEquals(environmentB, branchPath.systemContext().orElseThrow());
  }

  @Test
  void appendsToolSelectionAndDefinitionChangesAsRemoveThenAdd() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    Baseline baseline = seedThreadBaseline(store);
    ProviderToolDefinition readFile = definition("read_file", "Read a file");
    ProviderToolDefinition bash = definition("bash", "Run a command");
    SystemContext initial =
        new SystemContext(sectionsWithoutEnvironment(), List.of(readFile), List.of());
    appendSystemEntry(store, baseline, baseline(initial));

    EntryPath initialPath = path(store, baseline.threadId());
    SystemContext selectedTools =
        new SystemContext(sectionsWithoutEnvironment(), List.of(readFile, bash), List.of());
    SystemPayload added =
        initialPath.systemContext().orElseThrow().changesTo(selectedTools).orElseThrow();
    assertEquals(List.of(bash), added.addedTools());
    assertTrue(added.removedTools().isEmpty());
    assertTrue(added.sections().isEmpty());
    appendSystemEntry(store, baseline, added);

    EntryPath selectedPath = path(store, baseline.threadId());
    ProviderToolDefinition changedReadFile = definition("read_file", "Read a UTF-8 file");
    SystemContext changedDefinition =
        new SystemContext(sectionsWithoutEnvironment(), List.of(changedReadFile, bash), List.of());
    SystemPayload replaced =
        selectedPath.systemContext().orElseThrow().changesTo(changedDefinition).orElseThrow();
    assertEquals(List.of(changedReadFile), replaced.addedTools());
    assertEquals(List.of("read_file"), replaced.removedTools());
    assertTrue(replaced.sections().isEmpty());
    appendSystemEntry(store, baseline, replaced);

    EntryPath replacedPath = path(store, baseline.threadId());
    assertEquals(3, systemEntries(replacedPath));
    SystemContext reducedAfterDefinitionChange =
        new SystemContext(sectionsWithoutEnvironment(), List.of(bash, changedReadFile), List.of());
    assertEquals(reducedAfterDefinitionChange, replacedPath.systemContext().orElseThrow());
  }

  private static SystemPayload baseline(SystemContext context) {
    return new SystemPayload(context.sections(), context.tools(), List.of(), true);
  }

  private static Map<String, String> sectionsWithoutEnvironment() {
    Map<String, String> sections = new LinkedHashMap<>();
    sections.put("agent", "[agent] Use the configured assistant.");
    return sections;
  }

  private static Map<String, String> sections(String environment) {
    Map<String, String> sections = sectionsWithoutEnvironment();
    sections.put("environment", "[environment] Current environment: " + environment);
    return sections;
  }

  private static ProviderToolDefinition definition(String name, String description) {
    return new ProviderToolDefinition(
        name,
        description,
        new SchemaJsonCodec().encode(new InputSchema("arguments", Map.of(), Set.of(), false)));
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
