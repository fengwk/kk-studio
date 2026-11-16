package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Named SYSTEM state must render identically before and after object-key reordering. */
class SystemContextTest {

  @Test
  void keyOrderDoesNotChangeContextOrItsRenderedPrefix() {
    LinkedHashMap<String, String> original = new LinkedHashMap<>();
    original.put("agent", "Agent");
    original.put("harness", "Harness");
    original.put("paths", "Paths");
    original.put("environment", "Environment");
    Map<String, String> reversed = new LinkedHashMap<>();
    original.reversed().forEach(reversed::put);
    SystemContext before = new SystemContext(original, List.of(), List.of());
    SystemContext after = new SystemContext(reversed, List.of(), List.of());

    assertTrue(before.changesTo(after).isEmpty());
    assertEquals("Agent\n\nEnvironment\n\nHarness\n\nPaths", before.systemInstruction());
    assertEquals(before.systemInstruction(), after.systemInstruction());
    original.put("agent", "Changed outside the context");
    assertEquals("Agent", before.sections().get("agent"));
    assertThrows(UnsupportedOperationException.class, () -> before.sections().put("x", "X"));
  }

  @Test
  void repeatedStoredUpdatesDoNotAccumulateInstructions() {
    Map<String, String> sections = Map.of("agent", "Agent", "environment", "Environment");
    List<Entry> history = new ArrayList<>();
    history.add(entry(1, new SystemPayload(sections, List.of(), List.of(), true)));
    String expected = SystemContext.collapseSystemInstruction(history);
    // Replay already persisted duplicate deltas without rewriting or deleting history.
    for (int i = 2; i <= 34; i++) {
      history.add(entry(i, new SystemPayload(sections, List.of(), List.of(), false)));
      assertEquals(expected, SystemContext.collapseSystemInstruction(history));
    }
    assertEquals(34, history.size());
    assertEquals(expected, SystemContext.baselineSystemInstruction(history));
  }

  @Test
  void onlyChangedValuesAndRemovedNamesEnterTheDelta() {
    SystemContext before =
        new SystemContext(
            Map.of("agent", "Old", "harness", "Stable", "paths", "Remove"), List.of(), List.of());
    SystemContext after =
        new SystemContext(
            Map.of("agent", "New", "harness", "Stable", "environment", "Add"),
            List.of(),
            List.of());

    SystemPayload change = before.changesTo(after).orElseThrow();
    assertEquals(Map.of("agent", "New", "environment", "Add"), change.sections());
    assertEquals(List.of("paths"), change.removedSections());
    List<Entry> history =
        List.of(
            entry(1, new SystemPayload(before.sections(), List.of(), List.of(), true)),
            entry(2, change));
    assertEquals("New\n\nAdd\n\nStable", SystemContext.collapseSystemInstruction(history));
    assertEquals(after, SystemContext.reduce(history));
    assertEquals(
        "This section replaces the earlier [agent] section.\n\nNew\n\n"
            + "This section replaces the earlier [environment] section.\n\nAdd\n\n"
            + "The [paths] section has been removed.",
        SystemContext.renderChangeText(change));
  }

  /** A real tool-definition update must remain visible without rewriting stable prompt text. */
  @Test
  void toolOnlyReplacementKeepsTheInstructionAndReplacesTheDefinition() {
    ProviderToolDefinition oldTool = new ProviderToolDefinition("read", "Old description", "{}");
    ProviderToolDefinition newTool = new ProviderToolDefinition("read", "New description", "{}");
    SystemContext before =
        new SystemContext(Map.of("agent", "Stable"), List.of(oldTool), List.of());
    SystemContext after = new SystemContext(Map.of("agent", "Stable"), List.of(newTool), List.of());
    SystemPayload delta = before.changesTo(after).orElseThrow();
    assertTrue(delta.sections().isEmpty());
    assertEquals(List.of("read"), delta.removedTools());
    assertEquals(List.of(newTool), delta.addedTools());
    List<Entry> history =
        List.of(
            entry(1, new SystemPayload(before.sections(), before.tools(), List.of(), true)),
            entry(2, delta));
    assertEquals("Stable", SystemContext.collapseSystemInstruction(history));
    assertEquals(after, SystemContext.reduce(history));
  }

  @Test
  void aNewBaselineResetsSectionsAndToolsTogether() {
    ProviderToolDefinition oldTool = new ProviderToolDefinition("old", "Old tool", "{}");
    ProviderToolDefinition newTool = new ProviderToolDefinition("new", "New tool", "{}");
    List<Entry> history =
        List.of(
            entry(
                1, new SystemPayload(Map.of("old", "Obsolete"), List.of(oldTool), List.of(), true)),
            entry(
                2,
                new SystemPayload(Map.of("agent", "Current"), List.of(newTool), List.of(), true)));

    assertEquals("Current", SystemContext.collapseSystemInstruction(history));
    assertEquals("Current", SystemContext.baselineSystemInstruction(history));
    assertEquals(List.of(newTool), SystemContext.baselineTools(history));
    assertTrue(SystemContext.baselineHostedTools(history).isEmpty());
    assertEquals(List.of(newTool), SystemContext.reduce(history).tools());
  }

  @Test
  void missingBaselineStillFailsInsteadOfInventingAContext() {
    List<Entry> deltaOnly =
        List.of(
            entry(1, new SystemPayload(Map.of("agent", "Update"), List.of(), List.of(), false)));
    assertThrows(
        IllegalArgumentException.class, () -> SystemContext.collapseSystemInstruction(deltaOnly));
    assertThrows(
        IllegalArgumentException.class, () -> SystemContext.collapseSystemInstruction(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> SystemContext.baselineSystemInstruction(List.of()));
  }

  private static Entry entry(long sequence, SystemPayload payload) {
    return new Entry(
        new UUID(0, sequence), new UUID(1, 0), new UUID(0, sequence - 1), payload, Instant.EPOCH);
  }
}
