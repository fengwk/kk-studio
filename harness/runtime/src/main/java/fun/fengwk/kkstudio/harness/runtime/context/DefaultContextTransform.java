package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 消息路径投影：选择最后一个有效 compaction、保留其摘要与 retained entries。
 *
 * <p>不再从 Entry path fold Agent/Model/YOLO；运行时配置由 Thread 状态在 Context 构建时注入。
 */
public final class DefaultContextTransform {
  public List<SessionEntry> transform(List<SessionEntry> path) {
    path = List.copyOf(Objects.requireNonNull(path, "path"));
    int compactionIndex = lastEffectiveCompaction(path);
    return compactionIndex < 0
        ? withoutInvalidCompactions(path, path)
        : compactedEntries(path, compactionIndex);
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
