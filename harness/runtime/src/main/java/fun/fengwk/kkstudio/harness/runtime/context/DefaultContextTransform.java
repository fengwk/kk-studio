package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ModelChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ToolsetChangeEntryPayload;
import java.util.List;
import java.util.Objects;

/** 选择最后一个有效 compaction、保留其摘要和 retained entries，并解析最后生效配置。 */
public final class DefaultContextTransform {
  public ContextState transform(List<SessionEntry> path) {
    path = List.copyOf(Objects.requireNonNull(path, "path"));
    AgentRuntimeConfig config = resolveConfig(path);
    int compactionIndex = lastEffectiveCompaction(path);
    List<SessionEntry> entries =
        compactionIndex < 0 ? path : path.subList(compactionIndex, path.size());
    return new ContextState(config, entries);
  }

  private AgentRuntimeConfig resolveConfig(List<SessionEntry> path) {
    AgentRuntimeConfig config = null;
    for (SessionEntry entry : path) {
      SessionEntryPayload payload = entry.payload();
      if (payload instanceof AgentSnapshotEntryPayload snapshot) {
        config = AgentRuntimeConfig.from(snapshot.snapshot());
      } else if (payload instanceof ModelChangeEntryPayload modelChange) {
        if (config == null) {
          throw new ContextProjectionException("model change requires a preceding agent snapshot");
        }
        config = config.withModel(modelChange.modelId(), modelChange.variant());
      } else if (payload instanceof ToolsetChangeEntryPayload toolsetChange) {
        if (config == null) {
          throw new ContextProjectionException("toolset change requires a preceding agent snapshot");
        }
        config = config.withTools(toolsetChange.tools());
      }
    }
    if (config == null) {
      throw new ContextProjectionException("active path has no agent snapshot");
    }
    return config;
  }

  private int lastEffectiveCompaction(List<SessionEntry> path) {
    for (int index = path.size() - 1; index >= 0; index--) {
      if (!(path.get(index).payload() instanceof CompactionEntryPayload compaction)) {
        continue;
      }
      for (int retained = index + 1; retained < path.size(); retained++) {
        if (path.get(retained).entryId().equals(compaction.firstKeptEntryId())) {
          return index;
        }
      }
    }
    return -1;
  }
}
