package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Current system-prompt sections and model-visible tools reduced from SYSTEM entries on a path. */
public record SystemContext(
    Map<String, String> sections,
    List<ProviderToolDefinition> tools,
    List<SystemHostedTool> hostedTools) {

  public SystemContext {
    sections =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(sections, "sections")));
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    hostedTools = List.copyOf(Objects.requireNonNull(hostedTools, "hostedTools"));
  }

  /** Reduce baseline and all following deltas in path order. */
  public static SystemContext reduce(List<Entry> entries) {
    Map<String, String> sections = new LinkedHashMap<>();
    Map<String, ProviderToolDefinition> tools = new LinkedHashMap<>();
    Map<String, SystemHostedTool> hostedTools = new LinkedHashMap<>();
    boolean baselineSeen = false;
    for (Entry entry : entries) {
      if (!(entry.payload() instanceof SystemPayload change)) {
        continue;
      }
      if (change.baseline()) {
        sections.clear();
        tools.clear();
        hostedTools.clear();
        baselineSeen = true;
      } else if (!baselineSeen) {
        throw new IllegalArgumentException("SYSTEM change requires a baseline on the Entry path");
      }
      change.removedSections().forEach(sections::remove);
      sections.putAll(change.sections());
      change.removedTools().forEach(tools::remove);
      change.removedHostedTools().forEach(hostedTools::remove);
      for (SystemHostedTool tool : change.addedHostedTools()) {
        if (hostedTools.putIfAbsent(tool.name(), tool) != null) {
          throw new IllegalArgumentException("SYSTEM hosted tool already exists: " + tool.name());
        }
      }
      for (ProviderToolDefinition tool : change.addedTools()) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
          throw new IllegalArgumentException("SYSTEM tool already exists: " + tool.name());
        }
      }
    }
    if (!baselineSeen) {
      throw new IllegalArgumentException("Entry path has no SYSTEM baseline");
    }
    return new SystemContext(
        sections, new ArrayList<>(tools.values()), new ArrayList<>(hostedTools.values()));
  }

  /** Compose named sections in insertion order, separated by one blank line. */
  public String systemInstruction() {
    return String.join("\n\n", sections.values());
  }

  /** Return the latest baseline text, discarding any earlier compacted segment. */
  public static String baselineSystemInstruction(List<Entry> entries) {
    SystemPayload baseline = latestBaseline(entries);
    return String.join("\n\n", baseline.sections().values());
  }

  /** Return the model-visible tool definitions from the latest baseline. */
  public static List<ProviderToolDefinition> baselineTools(List<Entry> entries) {
    return latestBaseline(entries).addedTools();
  }

  public static List<SystemHostedTool> baselineHostedTools(List<Entry> entries) {
    return latestBaseline(entries).addedHostedTools();
  }

  private static SystemPayload latestBaseline(List<Entry> entries) {
    SystemPayload baseline = null;
    for (Entry entry : entries) {
      if (entry.payload() instanceof SystemPayload system && system.baseline()) {
        baseline = system;
      }
    }
    if (baseline == null) {
      throw new IllegalArgumentException("Entry path has no SYSTEM baseline");
    }
    return baseline;
  }

  /** Collapse a SYSTEM history into baseline text followed by every append-only change in order. */
  public static String collapseSystemInstruction(List<Entry> entries) {
    String baseline = null;
    List<String> changes = new ArrayList<>();
    for (Entry entry : entries) {
      if (!(entry.payload() instanceof SystemPayload system)) {
        continue;
      }
      if (system.baseline()) {
        baseline = String.join("\n\n", system.sections().values());
        changes.clear();
      } else {
        String changeText = renderChangeText(system);
        if (!changeText.isEmpty()) {
          changes.add(changeText);
        }
      }
    }
    if (baseline == null) {
      throw new IllegalArgumentException("Entry path has no SYSTEM baseline");
    }
    List<String> all = new ArrayList<>(changes.size() + 1);
    all.add(baseline);
    all.addAll(changes);
    return String.join("\n\n", all);
  }

  /** Render the exact text portion of one SYSTEM change in its stored section order. */
  public static String renderChangeText(SystemPayload change) {
    List<String> texts = new ArrayList<>();
    change
        .sections()
        .forEach(
            (name, text) -> {
              texts.add("This section replaces the earlier [" + name + "] section.");
              texts.add(text);
            });
    change
        .removedSections()
        .forEach(name -> texts.add("The [" + name + "] section has been removed."));
    return String.join("\n\n", texts);
  }

  /** Produce a delta containing only changed sections and tool definitions when context differs. */
  public Optional<SystemPayload> changesTo(SystemContext next) {
    Objects.requireNonNull(next, "next");
    Map<String, String> changed = new LinkedHashMap<>();
    boolean sectionOrderChanged =
        !new ArrayList<>(sections.keySet()).equals(new ArrayList<>(next.sections.keySet()));
    next.sections.forEach(
        (name, text) -> {
          if (sectionOrderChanged || !Objects.equals(sections.get(name), text)) {
            changed.put(name, text);
          }
        });
    List<String> removedSections =
        sections.keySet().stream().filter(name -> !next.sections.containsKey(name)).toList();
    Map<String, ProviderToolDefinition> oldTools = byName(tools);
    Map<String, ProviderToolDefinition> newTools = byName(next.tools);
    List<ProviderToolDefinition> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    Map<String, SystemHostedTool> oldHostedTools = hostedByName(hostedTools);
    Map<String, SystemHostedTool> newHostedTools = hostedByName(next.hostedTools);
    List<SystemHostedTool> addedHosted = new ArrayList<>();
    List<String> removedHosted = new ArrayList<>();
    next.tools.forEach(
        tool -> {
          if (!tool.equals(oldTools.get(tool.name()))) {
            added.add(tool);
          }
        });
    tools.forEach(
        tool -> {
          ProviderToolDefinition replacement = newTools.get(tool.name());
          if (replacement == null || !replacement.equals(tool)) {
            removed.add(tool.name());
          }
        });
    next.hostedTools.forEach(
        tool -> {
          if (!tool.equals(oldHostedTools.get(tool.name()))) {
            addedHosted.add(tool);
          }
        });
    hostedTools.forEach(
        tool -> {
          SystemHostedTool replacement = newHostedTools.get(tool.name());
          if (replacement == null || !replacement.equals(tool)) {
            removedHosted.add(tool.name());
          }
        });
    if (changed.isEmpty()
        && removedSections.isEmpty()
        && added.isEmpty()
        && removed.isEmpty()
        && addedHosted.isEmpty()
        && removedHosted.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new SystemPayload(
            changed, removedSections, added, removed, addedHosted, removedHosted, false));
  }

  private static Map<String, SystemHostedTool> hostedByName(List<SystemHostedTool> tools) {
    Map<String, SystemHostedTool> result = new LinkedHashMap<>();
    for (SystemHostedTool tool : tools) {
      if (result.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalArgumentException("hosted tool definitions must have unique names");
      }
    }
    return result;
  }

  private static Map<String, ProviderToolDefinition> byName(List<ProviderToolDefinition> tools) {
    Map<String, ProviderToolDefinition> result = new LinkedHashMap<>();
    for (ProviderToolDefinition tool : tools) {
      if (result.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalArgumentException("tool definitions must have unique names");
      }
    }
    return result;
  }
}
