package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * head 的一条不可变连续祖先链，可以是完整 root-to-head，也可以是某个祖先之后的增量。
 *
 * <p>{@code baseEntryId == null} 时 {@code entries} 必须是合法 {@link EntryPath}（ROOT 到 head）。{@code
 * baseEntryId != null} 时 {@code entries} 不含 base，且首节点的 parent 必须等于 base，其后父链连续、同 Session、ID
 * 不重复。空列表只在 base 就是 head 时成立。本类型不是 {@link EntryPath}：无 ROOT 的增量不得冒充完整路径。
 */
public record EntrySlice(UUID baseEntryId, List<Entry> entries) {

  public EntrySlice {
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    validate(baseEntryId, entries);
  }

  private static void validate(UUID baseEntryId, List<Entry> entries) {
    if (baseEntryId == null) {
      new EntryPath(entries);
      return;
    }
    if (entries.isEmpty()) {
      return;
    }
    Entry first = entries.get(0);
    if (!Objects.equals(first.parentEntryId(), baseEntryId)) {
      throw new IllegalArgumentException("entry slice must continue directly after its base");
    }
    UUID sessionId = first.sessionId();
    Set<UUID> ids = new HashSet<>();
    ids.add(baseEntryId);
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      if (!entry.sessionId().equals(sessionId)) {
        throw new IllegalArgumentException("entry slice must belong to one session");
      }
      if (entry.payload().type().isRoot()) {
        throw new IllegalArgumentException("incremental entry slice must not contain ROOT");
      }
      if (!ids.add(entry.id())) {
        throw new IllegalArgumentException("entry slice must not contain duplicate entry ids");
      }
      if (i > 0 && !Objects.equals(entry.parentEntryId(), entries.get(i - 1).id())) {
        throw new IllegalArgumentException("entry parent chain must be contiguous");
      }
    }
  }
}
