package fun.fengwk.kkstudio.ide.browser;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件节点本地 IDE 使用登记表。
 *
 * <p>只在真实物理连接建立后登记，断连即由该连接自己的 generation 删除；不写数据库、不做跨节点同步。每条连接第一次 accepted 的 documentId 固定下来，后续不同
 * document 一律拒绝。列表 revision 单调，同 revision 相同内容幂等，同 revision 不同内容与更低 revision 拒绝，更高 revision
 * 原子替换。查找严格绑定 {@link ConnectionIdentity}，因此旧连接关闭不可能删除或查询新连接的状态。
 */
public final class IdeBrowserUseRegistry {

  /** 一次 ide-use 登记的裁决结果。 */
  public enum Acceptance {
    /** 首次登记或更高 revision 已原子替换。 */
    ACCEPTED,
    /** 同 revision 且内容完全相同，保持原状态。 */
    UNCHANGED,
    /** 该连接已经固定到另一个 documentId。 */
    DOCUMENT_MISMATCH,
    /** 同 revision 内容不同，或 revision 未前进。 */
    REVISION_CONFLICT,
    /** 连接已关闭或 generation 已过期。 */
    DISCONNECTED
  }

  private static final class Entry {
    private final long generation;
    private boolean active = true;
    private IdeBrowserUse usage;

    private Entry(long generation) {
      this.generation = generation;
    }
  }

  private final ConcurrentHashMap<ConnectionIdentity, Entry> connections =
      new ConcurrentHashMap<>();
  private final AtomicLong generations = new AtomicLong();

  /**
   * 登记一条真实连接，返回只能删除本次登记的 generation。
   *
   * @throws IllegalStateException 同一 identity 已存在活动登记
   */
  public long register(ConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    long generation = generations.incrementAndGet();
    Entry entry = new Entry(generation);
    if (connections.putIfAbsent(identity, entry) != null) {
      throw new IllegalStateException("connection identity is already registered");
    }
    return generation;
  }

  /** 只删除与 {@code generation} 完全一致的登记；旧连接关闭不会影响新 generation。 */
  public void unregister(ConnectionIdentity identity, long generation) {
    Objects.requireNonNull(identity, "identity");
    Entry entry = connections.get(identity);
    if (entry == null) {
      return;
    }
    synchronized (entry) {
      if (entry.generation != generation || !entry.active) {
        return;
      }
      entry.active = false;
      entry.usage = null;
      connections.remove(identity, entry);
    }
  }

  /**
   * 接受一条完整 ide-use 列表。
   *
   * @return 裁决结果；仅 {@link Acceptance#ACCEPTED} 与 {@link Acceptance#UNCHANGED} 应 ack
   */
  public Acceptance accept(ConnectionIdentity identity, long generation, IdeBrowserUse usage) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(usage, "usage");
    Entry entry = connections.get(identity);
    if (entry == null) {
      return Acceptance.DISCONNECTED;
    }
    synchronized (entry) {
      if (!entry.active || entry.generation != generation) {
        return Acceptance.DISCONNECTED;
      }
      IdeBrowserUse current = entry.usage;
      if (current == null) {
        entry.usage = usage;
        return Acceptance.ACCEPTED;
      }
      if (!current.documentId().equals(usage.documentId())) {
        return Acceptance.DOCUMENT_MISMATCH;
      }
      if (usage.revision() < current.revision()) {
        return Acceptance.REVISION_CONFLICT;
      }
      if (usage.revision() == current.revision()) {
        return current.equals(usage) ? Acceptance.UNCHANGED : Acceptance.REVISION_CONFLICT;
      }
      entry.usage = usage;
      return Acceptance.ACCEPTED;
    }
  }

  /** 当前连接已确认的完整列表。 */
  public Optional<IdeBrowserUse> usage(ConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    Entry entry = connections.get(identity);
    if (entry == null) {
      return Optional.empty();
    }
    synchronized (entry) {
      if (!entry.active) {
        return Optional.empty();
      }
      return Optional.ofNullable(entry.usage);
    }
  }

  /** 在指定活动连接的固定 document 中查找完全相同的打开/恢复意图。 */
  public Optional<IdeBrowserUseView> findExactIntent(
      ConnectionIdentity identity,
      UUID documentId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    return usage(identity)
        .filter(usage -> usage.documentId().equals(documentId))
        .flatMap(usage -> usage.findExactIntent(environmentId, viewId, claimId));
  }

  /** 指定活动连接/document 中仍可用于续租的持有项。 */
  public Optional<IdeBrowserUse> activeBoundProofs(ConnectionIdentity identity, UUID documentId) {
    Objects.requireNonNull(documentId, "documentId");
    return usage(identity)
        .filter(usage -> usage.documentId().equals(documentId))
        .flatMap(IdeBrowserUse::activeBoundProofs);
  }
}
