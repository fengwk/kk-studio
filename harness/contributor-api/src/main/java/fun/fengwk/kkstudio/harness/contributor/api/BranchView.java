package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变分支视图：基于 root-to-head {@link EntryPath} 的只读状态投影。
 *
 * <p>视图对扩展隐藏底层完整历史路径（不暴露 EntryPath / transcript），仅允许通过结构化键 {@code (contributorId, customType)} 查询
 * CUSTOM Entry 的状态 payload。
 */
public final class BranchView {

  private final EntryPath path;

  public BranchView(EntryPath path) {
    this.path = Objects.requireNonNull(path, "path");
  }

  /** 返回路径上 {@code (contributorId, customType)} 匹配的 CUSTOM Entry payload 列表（root-to-head 顺序）。 */
  public List<CustomEntryPayload> customEntries(ContributorId contributorId, String customType) {
    Objects.requireNonNull(contributorId, "contributorId");
    Identifiers.requireCanonical(customType, "customType");
    List<CustomEntryPayload> matches = new ArrayList<>();
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof CustomEntryPayload custom
          && custom.contributorId().equals(contributorId.value())
          && custom.customType().equals(customType)) {
        matches.add(custom);
      }
    }
    return List.copyOf(matches);
  }

  /** 返回路径上 {@code (contributorId, customType)} 匹配的最新（head 最近）CUSTOM Entry payload；没有匹配返回 empty。 */
  public Optional<CustomEntryPayload> latestCustomEntry(
      ContributorId contributorId, String customType) {
    Objects.requireNonNull(contributorId, "contributorId");
    Identifiers.requireCanonical(customType, "customType");
    for (int i = path.entries().size() - 1; i >= 0; i--) {
      if (path.entries().get(i).payload() instanceof CustomEntryPayload custom
          && custom.contributorId().equals(contributorId.value())
          && custom.customType().equals(customType)) {
        return Optional.of(custom);
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    BranchView other = (BranchView) obj;
    return path.equals(other.path);
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  @Override
  public String toString() {
    return "BranchView[head=" + path.head().id() + ", size=" + path.entries().size() + "]";
  }
}
