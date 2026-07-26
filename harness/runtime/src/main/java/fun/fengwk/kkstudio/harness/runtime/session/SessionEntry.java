package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;

import java.util.Objects;

/**
 * Session Tree 上不可变的追加节点。
 *
 * <p>ROOT 没有 parent，其他 Entry 必须指向一个 parent。
 */
public record SessionEntry(long id, Long parentEntryId, EntryPayload payload) {

  public SessionEntry {
    if (id <= 0) {
      throw new IllegalArgumentException("entry id must be positive");
    }
    if (parentEntryId != null && parentEntryId <= 0) {
      throw new IllegalArgumentException("parentEntryId must be positive when present");
    }
    payload = Objects.requireNonNull(payload, "payload");
    if (payload.type().isRoot()) {
      if (parentEntryId != null) {
        throw new IllegalArgumentException("ROOT entry must not carry parentEntryId");
      }
    } else if (parentEntryId == null) {
      throw new IllegalArgumentException("non-ROOT entry must carry parentEntryId");
    }
  }
}
