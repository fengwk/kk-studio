package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ModelChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ToolsetChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.YoloChangeEntryPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 选择最后一个有效 compaction、保留其摘要和 retained entries，并解析最后生效配置（含 YOLO）。 */
public final class DefaultContextTransform {
  public ContextState transform(List<SessionEntry> path) {
    path = List.copyOf(Objects.requireNonNull(path, "path"));
    AgentRuntimeConfig config = resolveConfig(path);
    int compactionIndex = lastEffectiveCompaction(path);
    List<SessionEntry> entries =
        compactionIndex < 0
            ? withoutInvalidCompactions(path, path)
            : compactedEntries(path, compactionIndex);
    return new ContextState(config, entries);
  }

  private AgentRuntimeConfig resolveConfig(List<SessionEntry> path) {
    AgentRuntimeConfig config = null;
    for (SessionEntry entry : path) {
      SessionEntryPayload payload = entry.payload();
      if (payload instanceof AgentSnapshotEntryPayload snapshot) {
        config = AgentRuntimeConfig.from(snapshot.agentDefinitionId(), snapshot.snapshot());
      } else if (payload instanceof ModelChangeEntryPayload modelChange) {
        if (config == null) {
          throw new ContextProjectionException("model change requires a preceding agent snapshot");
        }
        config = config.withModel(modelChange.modelId(), modelChange.variant());
      } else if (payload instanceof ToolsetChangeEntryPayload toolsetChange) {
        if (config == null) {
          throw new ContextProjectionException(
              "toolset change requires a preceding agent snapshot");
        }
        config = config.withTools(toolsetChange.tools());
      } else if (payload instanceof YoloChangeEntryPayload yoloChange) {
        if (config == null) {
          throw new ContextProjectionException("yolo change requires a preceding agent snapshot");
        }
        config = config.withYolo(yoloChange.yoloEnabled());
      }
    }
    if (config == null) {
      throw new ContextProjectionException("active path has no agent snapshot");
    }
    return config;
  }

  private List<SessionEntry> compactedEntries(List<SessionEntry> path, int compactionIndex) {
    CompactionEntryPayload compaction =
        (CompactionEntryPayload) path.get(compactionIndex).payload();
    int firstKeptIndex = firstKeptIndex(path, compactionIndex, compaction.firstKeptEntryId());
    if (firstKeptIndex < 0) {
      throw new IllegalStateException("effective compaction must retain an ancestor");
    }
    List<SessionEntry> result = new ArrayList<>();
    result.add(path.get(compactionIndex));
    result.addAll(path.subList(firstKeptIndex, compactionIndex));
    result.addAll(path.subList(compactionIndex + 1, path.size()));
    return withoutInvalidCompactions(result, path);
  }

  private List<SessionEntry> withoutInvalidCompactions(
      List<SessionEntry> entries, List<SessionEntry> fullPath) {
    return entries.stream()
        .filter(
            entry ->
                !(entry.payload() instanceof CompactionEntryPayload)
                    || isEffectiveCompaction(fullPath, fullPath.indexOf(entry)))
        .toList();
  }

  private int lastEffectiveCompaction(List<SessionEntry> path) {
    for (int index = path.size() - 1; index >= 0; index--) {
      if (isEffectiveCompaction(path, index)) {
        return index;
      }
    }
    return -1;
  }

  private boolean isEffectiveCompaction(List<SessionEntry> path, int index) {
    return path.get(index).payload() instanceof CompactionEntryPayload compaction
        && firstKeptIndex(path, index, compaction.firstKeptEntryId()) >= 0;
  }

  private int firstKeptIndex(List<SessionEntry> path, int compactionIndex, long firstKeptEntryId) {
    for (int index = 0; index < compactionIndex; index++) {
      if (path.get(index).id() == firstKeptEntryId) {
        return index;
      }
    }
    return -1;
  }
}
