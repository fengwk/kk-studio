package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable baseline or append-only delta of the model request context. */
public record SystemPayload(
    Map<String, String> sections,
    List<String> removedSections,
    List<ProviderToolDefinition> addedTools,
    List<String> removedTools,
    List<SystemHostedTool> addedHostedTools,
    List<String> removedHostedTools,
    boolean baseline)
    implements EntryPayload {

  public SystemPayload(
      Map<String, String> sections,
      List<String> removedSections,
      List<ProviderToolDefinition> addedTools,
      List<String> removedTools,
      boolean baseline) {
    this(sections, removedSections, addedTools, removedTools, List.of(), List.of(), baseline);
  }

  public SystemPayload(
      Map<String, String> sections,
      List<ProviderToolDefinition> addedTools,
      List<String> removedTools,
      boolean baseline) {
    this(sections, List.of(), addedTools, removedTools, List.of(), List.of(), baseline);
  }

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
    removedSections = List.copyOf(Objects.requireNonNull(removedSections, "removedSections"));
    if (removedSections.stream().anyMatch(name -> name == null || name.isBlank())) {
      throw new IllegalArgumentException("removedSections must contain non-blank names");
    }
    addedTools = List.copyOf(Objects.requireNonNull(addedTools, "addedTools"));
    removedTools = List.copyOf(Objects.requireNonNull(removedTools, "removedTools"));
    addedHostedTools = List.copyOf(Objects.requireNonNull(addedHostedTools, "addedHostedTools"));
    removedHostedTools =
        List.copyOf(Objects.requireNonNull(removedHostedTools, "removedHostedTools"));
    if (baseline
        && (!removedSections.isEmpty()
            || !removedTools.isEmpty()
            || !removedHostedTools.isEmpty()
            || sections.isEmpty())) {
      throw new IllegalArgumentException("baseline SYSTEM must contain sections and no removals");
    }
    if (removedHostedTools.stream().anyMatch(name -> name == null || name.isBlank())) {
      throw new IllegalArgumentException("removedHostedTools must contain non-blank names");
    }
    if (addedHostedTools.stream().map(SystemHostedTool::name).distinct().count()
        != addedHostedTools.size()) {
      throw new IllegalArgumentException("addedHostedTools must have unique names");
    }
    if (!baseline
        && sections.isEmpty()
        && removedSections.isEmpty()
        && addedTools.isEmpty()
        && removedTools.isEmpty()
        && addedHostedTools.isEmpty()
        && removedHostedTools.isEmpty()) {
      throw new IllegalArgumentException("SYSTEM change must change text or tools");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.SYSTEM;
  }
}
