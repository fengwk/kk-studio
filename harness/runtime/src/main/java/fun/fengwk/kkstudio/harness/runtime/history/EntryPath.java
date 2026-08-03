package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一条不可变的 root-to-head Entry 链（Thread head cursor 指向的完整历史路径）。
 *
 * <p>构造时防御性拷贝并校验：非空；同一 Session；ROOT 在前且唯一；parent 链连续（每个非 ROOT Entry 的 parentEntryId 等于前一个 Entry 的
 * id）；ID 不重复；createdAt 不早于 parent。Turn 顺序与 TURN_END outcome 前置条件委托 {@link TurnPathValidator}：ROOT
 * 后所有非 ROOT entry 必须在 open TURN_START 内；TURN_END 只能关闭当前 open TURN_START 且 turnStartEntryId 匹配；未关闭
 * TURN_START 后不得出现第二个 TURN_START；input / Assistant result / ToolResult 前缀顺序按 outcome 校验。路径可以在任意
 * prefix 截断（最后一个 Entry 即 head，由调用方按 root-to-head 顺序传入）。不提供泛化 tree / repository API。
 */
public record EntryPath(List<Entry> entries) {

  public EntryPath {
    Objects.requireNonNull(entries, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entry path must not be empty");
    }
    entries = List.copyOf(entries);
    validate(entries);
  }

  private static void validate(List<Entry> entries) {
    long sessionId = entries.get(0).sessionId();
    boolean rootSeen = false;
    Set<Long> ids = new HashSet<>();
    TurnPathValidator turns = new TurnPathValidator();
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      if (entry.sessionId() != sessionId) {
        throw new IllegalArgumentException("entry path must belong to one session");
      }
      if (!ids.add(entry.id())) {
        throw new IllegalArgumentException("entry path must not contain duplicate entry ids");
      }
      if (entry.payload().type().isRoot()) {
        if (rootSeen) {
          throw new IllegalArgumentException("entry path must contain exactly one ROOT");
        }
        rootSeen = true;
      } else {
        if (i == 0) {
          throw new IllegalArgumentException("entry path must start with ROOT");
        }
        Entry parent = entries.get(i - 1);
        if (!Objects.equals(entry.parentEntryId(), parent.id())) {
          throw new IllegalArgumentException("entry parent chain must be contiguous");
        }
        if (entry.createdAt().isBefore(parent.createdAt())) {
          throw new IllegalArgumentException("entry createdAt must not precede its parent");
        }
        turns.visit(entry);
      }
    }
  }

  /** 返回 ROOT Entry。 */
  public Entry root() {
    return entries.get(0);
  }

  /** 返回 head Entry（路径最后一个 Entry）。 */
  public Entry head() {
    return entries.get(entries.size() - 1);
  }

  /** 返回沿路径最近的 ROOT/TURN_START 完整 settings snapshot；ROOT settings 由构造不变量保证非空。 */
  public BranchSettings baseSettings() {
    BranchSettings settings = ((RootPayload) root().payload()).settings();
    for (Entry entry : entries) {
      if (entry.payload() instanceof TurnStartPayload start) {
        settings = start.settings();
      }
    }
    return settings;
  }

  /** 返回当前尚未被 TURN_END 关闭的 TURN_START Entry；没有 open Turn 时返回 {@link Optional#empty()}。 */
  public Optional<Entry> openTurnStart() {
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry entry = entries.get(i);
      if (entry.payload() instanceof TurnStartPayload) {
        return Optional.of(entry);
      }
      if (entry.payload() instanceof TurnEndPayload) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
