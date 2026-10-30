package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
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
