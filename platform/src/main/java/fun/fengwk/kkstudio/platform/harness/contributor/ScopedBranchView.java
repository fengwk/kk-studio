package fun.fengwk.kkstudio.platform.harness.contributor;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 针对特定 Contributor 作用域的只读 {@link BranchView} 实现。
 *
 * <p>按 root-to-head 顺序遍历 Entry 列表，只暴露属于指定 {@code contributorId} 的 {@link CustomEntryPayload}。
 */
public final class ScopedBranchView implements BranchView {

  private final List<Entry> entries;
  private final String contributorId;

  public ScopedBranchView(List<Entry> entries, String contributorId) {
    this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    this.contributorId = Objects.requireNonNull(contributorId, "contributorId");
  }

  @Override
  public List<CustomStateSnapshot> customEntries(String customType) {
    Objects.requireNonNull(customType, "customType");
    List<CustomStateSnapshot> snapshots = new ArrayList<>();
    for (Entry entry : entries) {
      if (entry.payload() instanceof CustomEntryPayload custom
          && custom.contributorId().equals(contributorId)
          && custom.customType().equals(customType)) {
        snapshots.add(new CustomStateSnapshot(custom.schemaVersion(), custom.dataJson()));
      }
    }
    return List.copyOf(snapshots);
  }

  @Override
  public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
    List<CustomStateSnapshot> snapshots = customEntries(customType);
    return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.getLast());
  }
}
