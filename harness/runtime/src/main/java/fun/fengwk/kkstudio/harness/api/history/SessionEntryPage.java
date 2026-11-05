package fun.fengwk.kkstudio.harness.api.history;

import java.util.List;
import java.util.Objects;

/**
 * Session Entry 的一页稳定 keyset 结果。
 *
 * <p>{@code entries} 按存储追加顺序（创建顺序）升序；同一 Session 内父节点的追加序号必小于子节点，因此父节点不会晚于子节点出现。{@code hasMore}
 * 表示本页之后仍有 Entry，由 storage 多取一条判定。
 */
public record SessionEntryPage(List<Entry> entries, boolean hasMore) {

  public SessionEntryPage {
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
  }
}
