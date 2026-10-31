package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Entry 聚合原语：append-only 不可变历史。
 *
 * <p>写入前用完整 {@link EntryPath} 校验（每 Session 一个 ROOT 且 ROOT 先于其他 Entry、parent 连续且同 Session、createdAt
 * 顺序与 turn / tool-prefix 结构），删除按叶子优先逐层剥离。
 */
final class InMemoryEntryStore implements EntryStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryEntryStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public void insert(Entry entry) {
    tx.checkOpen();
    Objects.requireNonNull(entry, "entry");
    HarnessStoreTime.requireMillisecondPrecision(entry.createdAt());
    InMemoryHarnessTransaction.requireAbsent(state.entries, entry.id(), "entry");
    if (!state.sessions.containsKey(entry.sessionId())) {
      throw new IllegalArgumentException("session " + entry.sessionId() + " does not exist");
    }
    boolean root = entry.payload().type().isRoot();
    if (root) {
      if (hasRoot(entry.sessionId())) {
        throw new IllegalArgumentException(
            "session " + entry.sessionId() + " already has a ROOT entry");
      }
      new EntryPath(List.of(entry));
    } else {
      if (!hasRoot(entry.sessionId())) {
        throw new IllegalArgumentException(
            "session " + entry.sessionId() + " must have a ROOT entry before any other entry");
      }
      // 写入前校验 parent path + new entry 的完整 EntryPath（turn / tool-prefix 结构不能以非法序列进入 store）。
      EntryPath parentPath = loadPath(entry.parentEntryId());
      List<Entry> nextPath = new ArrayList<>(parentPath.entries());
      nextPath.add(entry);
      new EntryPath(nextPath);
    }
    state.entries.put(entry.id(), entry);
  }

  private boolean hasRoot(UUID sessionId) {
    return findRoot(sessionId).isPresent();
  }

  @Override
  public Optional<Entry> find(UUID id) {
    tx.checkOpen();
    return Optional.ofNullable(state.entries.get(id));
  }

  @Override
  public Optional<Entry> findRoot(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    for (Entry entry : state.entries.values()) {
      if (entry.sessionId().equals(sessionId) && entry.payload().type().isRoot()) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  @Override
  public EntryPath loadPath(UUID headEntryId) {
    tx.checkOpen();
    List<Entry> path = new ArrayList<>();
    UUID cursor = headEntryId;
    while (cursor != null) {
      Entry entry = tx.requireExistingEntry(cursor);
      path.add(entry);
      cursor = entry.payload().type().isRoot() ? null : entry.parentEntryId();
    }
    Collections.reverse(path);
    return new EntryPath(path);
  }

  /**
   * 只回溯到当前状态所需锚点：head、非控制 head、open 或被 TURN_END 引用的 TURN_START，以及最近 settings。 不调用 {@link
   * #loadPath(UUID)}。
   */
  @Override
  public EntryCurrentState loadCurrent(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Entry head = tx.requireExistingEntry(headEntryId);
    Entry nonControl = null;
    Entry openTurn = null;
    boolean turnResolved = false;
    BranchSettings settings = null;
    Entry referenced = null;
    boolean referencedResolved = false;
    UUID cursor = headEntryId;
    while (cursor != null) {
      Entry entry = tx.requireExistingEntry(cursor);
      if (nonControl == null && !isControl(entry)) {
        nonControl = entry;
        referencedResolved = !(entry.payload() instanceof TurnEndPayload);
      }
      if (!turnResolved) {
        if (entry.payload() instanceof TurnStartPayload) {
          openTurn = entry;
          turnResolved = true;
        } else if (entry.payload() instanceof TurnEndPayload) {
          turnResolved = true;
        }
      }
      if (settings == null) {
        settings = settingsOf(entry);
      }
      if (nonControl != null
          && !referencedResolved
          && nonControl.payload() instanceof TurnEndPayload end
          && entry.id().equals(end.turnStartEntryId())
          && entry.payload() instanceof TurnStartPayload) {
        referenced = entry;
        referencedResolved = true;
      }
      if (nonControl != null && turnResolved && settings != null && referencedResolved) {
        break;
      }
      cursor = entry.payload().type().isRoot() ? null : entry.parentEntryId();
    }
    if (nonControl == null || settings == null || !referencedResolved) {
      throw new IllegalArgumentException("entry path for " + headEntryId + " is incomplete");
    }
    return new EntryCurrentState(head, nonControl, openTurn, referenced, settings);
  }

  /** 收集祖先 Entry 引用后交给参考算法，不构造 {@link EntryPath}，也不复制计数规则。 */
  @Override
  public EntryPathMetrics loadPathMetrics(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    List<Entry> ancestors = new ArrayList<>();
    UUID cursor = headEntryId;
    while (cursor != null) {
      Entry entry = tx.requireExistingEntry(cursor);
      ancestors.add(entry);
      cursor = entry.payload().type().isRoot() ? null : entry.parentEntryId();
    }
    Collections.reverse(ancestors);
    return EntryPathMetrics.from(ancestors);
  }

  /** 命中 candidate 即停止；未知或其它分支为 false，缺失 head 沿完整路径读取拒绝。 */
  @Override
  public boolean isAncestor(UUID headEntryId, UUID candidateEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(candidateEntryId, "candidateEntryId");
    tx.requireExistingEntry(headEntryId);
    if (state.entries.get(candidateEntryId) == null) {
      return false;
    }
    UUID cursor = headEntryId;
    while (cursor != null) {
      Entry entry = tx.requireExistingEntry(cursor);
      if (entry.id().equals(candidateEntryId)) {
        return true;
      }
      cursor = entry.payload().type().isRoot() ? null : entry.parentEntryId();
    }
    return false;
  }

  private static boolean isControl(Entry entry) {
    return entry.payload() instanceof NotificationPayload
        || entry.payload() instanceof SettingsPayload
        || entry.payload() instanceof ForkPayload;
  }

  private static BranchSettings settingsOf(Entry entry) {
    if (entry.payload() instanceof TurnStartPayload start
        && start.reason() != TurnStartReason.COMPACTION) {
      return start.settings();
    }
    if (entry.payload() instanceof SettingsPayload applied) {
      return applied.settings();
    }
    if (entry.payload() instanceof RootPayload root) {
      return root.settings();
    }
    return null;
  }

  /** 内存增量读取与 PostgreSQL 同语义：从 head 回溯，命中祖先即停且不含游标；非祖先、另一 Session 或未知游标回到完整链。 */
  @Override
  public EntrySlice loadSlice(UUID headEntryId, UUID afterEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    if (afterEntryId == null) {
      return new EntrySlice(null, loadPath(headEntryId).entries());
    }
    List<Entry> suffix = new ArrayList<>();
    UUID cursor = headEntryId;
    while (cursor != null) {
      Entry entry = tx.requireExistingEntry(cursor);
      if (entry.id().equals(afterEntryId)) {
        Collections.reverse(suffix);
        return new EntrySlice(afterEntryId, suffix);
      }
      suffix.add(entry);
      cursor = entry.payload().type().isRoot() ? null : entry.parentEntryId();
    }
    Collections.reverse(suffix);
    return new EntrySlice(null, suffix);
  }

  @Override
  public List<Entry> loadContributorCustom(UUID headEntryId, String contributorId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    Objects.requireNonNull(contributorId, "contributorId");
    return loadPath(headEntryId).entries().stream()
        .filter(
            entry ->
                entry.payload() instanceof CustomEntryPayload custom
                    && contributorId.equals(custom.contributorId()))
        .toList();
  }

  @Override
  public BranchSettings loadBranchSettings(UUID headEntryId) {
    tx.checkOpen();
    Objects.requireNonNull(headEntryId, "headEntryId");
    // 参考实现直接复用完整路径语义：与 PostgreSQL 窄查询保持同一 settings 结果。
    return loadPath(headEntryId).baseSettings();
  }

  @Override
  public List<Entry> loadBySession(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (!state.sessions.containsKey(sessionId)) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    List<Entry> entries = new ArrayList<>();
    for (Entry entry : state.entries.values()) {
      if (entry.sessionId().equals(sessionId)) {
        entries.add(entry);
      }
    }
    entries.sort(
        Comparator.comparing(Entry::createdAt).thenComparing(Entry::id, UuidOrder.COMPARATOR));
    return List.copyOf(entries);
  }

  @Override
  public Optional<Entry> findToolResultByInvocationId(UUID sessionId, UUID invocationId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(invocationId, "invocationId");
    Entry found = null;
    for (Entry entry : state.entries.values()) {
      if (!entry.sessionId().equals(sessionId)
          || !(entry.payload() instanceof MessagePayload message)
          || message.toolResultMetadata() == null
          || !invocationId.equals(message.toolResultMetadata().invocationId())) {
        continue;
      }
      if (found != null) {
        throw new IllegalStateException(
            "multiple tool result entries reference invocation " + invocationId);
      }
      found = entry;
    }
    return Optional.ofNullable(found);
  }

  @Override
  public int delete(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    int total = 0;
    while (true) {
      // 叶子优先：只删除本 Session 内未被任何（同 Session）Entry 引用为 parent 的 Entry，逐层剥到 ROOT。
      Set<UUID> referencedParents = new HashSet<>();
      for (Entry entry : state.entries.values()) {
        if (entry.sessionId().equals(sessionId) && entry.parentEntryId() != null) {
          referencedParents.add(entry.parentEntryId());
        }
      }
      Set<UUID> deletable = new HashSet<>();
      for (Entry entry : state.entries.values()) {
        if (entry.sessionId().equals(sessionId) && !referencedParents.contains(entry.id())) {
          deletable.add(entry.id());
        }
      }
      if (deletable.isEmpty()) {
        return total;
      }
      for (UUID entryId : deletable) {
        state.entries.remove(entryId);
        total++;
      }
    }
  }
}
