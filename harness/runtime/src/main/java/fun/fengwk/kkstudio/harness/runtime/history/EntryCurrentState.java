package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;
import java.util.Optional;

/**
 * 当前分类所需的 Entry 事实，不携带历史消息、计数或持久化快照。
 *
 * <p>{@code openTurnStartEntry} 可空。{@code referencedTurnStartEntry} 仅在去掉尾部控制 Entry 后的 head 是 {@code
 * TURN_END} 时存在，并且必须是该 {@code TURN_END} 引用、位于当前祖先链上的 {@code TURN_START}。
 */
public record EntryCurrentState(
    Entry head,
    Entry headIgnoringTrailingControlEntries,
    Entry openTurnStartEntry,
    Entry referencedTurnStartEntry,
    BranchSettings baseSettings) {

  public EntryCurrentState {
    head = Objects.requireNonNull(head, "head");
    headIgnoringTrailingControlEntries =
        Objects.requireNonNull(
            headIgnoringTrailingControlEntries, "headIgnoringTrailingControlEntries");
    baseSettings = Objects.requireNonNull(baseSettings, "baseSettings");
  }

  /** 当前尚未关闭的 {@code TURN_START}；没有 open Turn 时为空。 */
  public Optional<Entry> openTurnStart() {
    return Optional.ofNullable(openTurnStartEntry);
  }

  /** 按完整路径派生当前状态；语义与 {@link EntryPath} 的对应访问器一致。 */
  public static EntryCurrentState from(EntryPath path) {
    Objects.requireNonNull(path, "path");
    Entry headIgnoringControl = path.headIgnoringTrailingControlEntries();
    Entry referenced = null;
    if (headIgnoringControl.payload() instanceof TurnEndPayload end) {
      referenced = referencedTurnStart(path, end);
    }
    return new EntryCurrentState(
        path.head(),
        headIgnoringControl,
        path.openTurnStart().orElse(null),
        referenced,
        path.baseSettings());
  }

  private static Entry referencedTurnStart(EntryPath path, TurnEndPayload end) {
    for (Entry entry : path.entries()) {
      if (entry.id().equals(end.turnStartEntryId())
          && entry.payload() instanceof TurnStartPayload) {
        return entry;
      }
    }
    throw new IllegalArgumentException(
        "TURN_END " + end.turnStartEntryId() + " does not reference a TURN_START on the path");
  }
}
