package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Current system-prompt sections and tools reduced from SYSTEM entries on an Entry path. */
public record SystemContext(Map<String, String> sections, List<ToolBinding> tools) {

  public SystemContext {
    sections =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(sections, "sections")));
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }

  /** Reduce baseline and all following deltas in path order. */
  public static SystemContext reduce(List<Entry> entries) {
    Map<String, String> sections = new LinkedHashMap<>();
    Map<String, ToolBinding> tools = new LinkedHashMap<>();
    boolean baselineSeen = false;
    for (Entry entry : entries) {
      if (!(entry.payload() instanceof SystemPayload change)) {
        continue;
      }
      if (change.baseline()) {
        sections.clear();
        tools.clear();
        baselineSeen = true;
      } else if (!baselineSeen) {
        throw new IllegalArgumentException("SYSTEM change requires a baseline on the Entry path");
      }
      change.removedSections().forEach(sections::remove);
      sections.putAll(change.sections());
      change.removedTools().forEach(tools::remove);
      for (ToolBinding binding : change.addedTools()) {
        String name = binding.descriptor().name();
        if (tools.putIfAbsent(name, binding) != null) {
          throw new IllegalArgumentException("SYSTEM tool already exists: " + name);
        }
      }
    }
    if (!baselineSeen) {
      throw new IllegalArgumentException("Entry path has no SYSTEM baseline");
    }
    return new SystemContext(sections, new ArrayList<>(tools.values()));
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

  /** Return the tool definitions from the latest baseline. */
  public static List<ToolBinding> baselineTools(List<Entry> entries) {
    return latestBaseline(entries).addedTools();
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
    Map<String, ToolBinding> oldTools = byName(tools);
    Map<String, ToolBinding> newTools = byName(next.tools);
    List<ToolBinding> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    next.tools.forEach(
        binding -> {
          ToolBinding old = oldTools.get(binding.descriptor().name());
          if (!binding.equals(old)) {
            added.add(binding);
          }
        });
    tools.forEach(
        binding -> {
          ToolBinding replacement = newTools.get(binding.descriptor().name());
          if (replacement == null || !replacement.equals(binding)) {
            removed.add(binding.descriptor().name());
          }
        });
    if (changed.isEmpty() && removedSections.isEmpty() && added.isEmpty() && removed.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new SystemPayload(changed, removedSections, added, removed, false));
  }

  private static Map<String, ToolBinding> byName(List<ToolBinding> bindings) {
    Map<String, ToolBinding> result = new LinkedHashMap<>();
    for (ToolBinding binding : bindings) {
      if (result.putIfAbsent(binding.descriptor().name(), binding) != null) {
        throw new IllegalArgumentException("tool bindings must have unique names");
      }
    }
    return result;
  }
}
