package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable baseline or append-only delta of the model request context. */
public record SystemPayload(
    Map<String, String> sections,
    List<ToolBinding> addedTools,
    List<String> removedTools,
    boolean baseline)
    implements EntryPayload {

  public SystemPayload {
    Objects.requireNonNull(sections, "sections");
    LinkedHashMap<String, String> copiedSections = new LinkedHashMap<>();
    sections.forEach(
        (name, text) -> {
          if (name == null || name.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("system sections require non-blank names and text");
          }
          copiedSections.put(name, text);
        });
    sections = Collections.unmodifiableMap(copiedSections);
    addedTools = List.copyOf(Objects.requireNonNull(addedTools, "addedTools"));
    removedTools = List.copyOf(Objects.requireNonNull(removedTools, "removedTools"));
    if (baseline && (!removedTools.isEmpty() || sections.isEmpty())) {
      throw new IllegalArgumentException("baseline SYSTEM must contain sections and no removals");
    }
    if (!baseline && sections.isEmpty() && addedTools.isEmpty() && removedTools.isEmpty()) {
      throw new IllegalArgumentException("SYSTEM change must change text or tools");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.SYSTEM;
  }
}
