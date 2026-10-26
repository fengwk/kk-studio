package fun.fengwk.kkstudio.ide.stream;

import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 反向数据面的「浏览器 exchange ↔ Daemon 反向连接」一次性配对注册表。
 *
 * <p>每个 HTTP exchange / IDE WebSocket 先由 Gateway 铸造 stream ticket 并登记一个 pending 配对（绑定 Daemon 实例代际
 * {@link DaemonConnectionIdentity}），随后通过 {@code OPEN_STREAM} 命令把 ticket 交给 Daemon；Daemon 反向连上
 * stream endpoint 后由 {@link #pair(String, AutoCloseable)} 交付 peer，等待方 {@link Pairing#awaitPeer()}
 * 才能继续转发。
 *
 * <p><b>超时与代际结束的收敛：</b>等待超时的 pending 配对会被原子终结——关闭可能的 peer、执行 {@code abandon} 回调（放弃在途 {@code
 * OPEN_STREAM}）并向等待方抛出 {@link IdeStreamPairingException}；已交付 peer 的配对由 {@link Pairing#close()} 或
 * {@link #closeGeneration} 关闭 peer。另一个 Daemon 实例接管时按代际关闭全部 pending 与 active 配对，因此不会出现「只 revoke
 * 票据、流仍在转发」；同一实例断线重连不结束代际，已建立的反向流照常转发。
 *
 * <p>ticket 一次性：配对成功或终结后即从表中移除，迟到的反向连接（ticket 已消费）会由 {@link #pair} 返回 {@code false}，由适配层
 * fail-closed 关闭该连接。所有状态由单一锁保护；peer 的关闭与 abandon 回调在锁外执行。
 *
 * @param <P> 反向连接 peer 类型（如 WebSocket 会话句柄）；由适配层持有，注册表只负责生命周期
 */
public final class IdeStreamPairingRegistry<P extends AutoCloseable> {

  private final Object lock = new Object();
  private final Map<String, Entry<P>> byTicket = new HashMap<>();
  private final Map<DaemonConnectionIdentity, Set<Entry<P>>> byGeneration = new HashMap<>();

  /** 登记一个 pending 配对；ticket 必须已由 {@link IdeStreamTicketService} 铸造并绑定同一代际。 */
  public Pairing open(
      String ticket, DaemonConnectionIdentity generation, Duration timeout, Runnable abandon) {
    Objects.requireNonNull(ticket, "ticket");
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(abandon, "abandon");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    Entry<P> entry = new Entry<>(ticket, generation, abandon, timeout);
    synchronized (lock) {
      if (byTicket.containsKey(ticket)) {
        throw new IllegalStateException("stream ticket is already pending");
      }
      byTicket.put(ticket, entry);
      byGeneration.computeIfAbsent(entry.generation, ignored -> new HashSet<>()).add(entry);
    }
    return new Pairing(entry);
  }

  /** 交付 Daemon 反向连接；ticket 未知/已终结/已配对时返回 false（适配层必须关闭该 peer）。 */
  public boolean pair(String ticket, P peer) {
    Objects.requireNonNull(ticket, "ticket");
    Objects.requireNonNull(peer, "peer");
    synchronized (lock) {
      Entry<P> entry = byTicket.get(ticket);
      if (entry == null) {
        return false;
      }
      synchronized (entry) {
        if (entry.closed || entry.peer != null) {
          return false;
        }
        entry.peer = peer;
        entry.paired = true;
        entry.notifyAll();
        return true;
      }
    }
  }

  /** 代际结束（另一个 Daemon 实例接管）：关闭该代际全部 pending 与 active 配对。 */
  public void closeGeneration(DaemonConnectionIdentity generation) {
    Objects.requireNonNull(generation, "generation");
    List<Entry<P>> targets;
    synchronized (lock) {
      Set<Entry<P>> entries = byGeneration.get(generation);
      targets = entries == null ? List.of() : new ArrayList<>(entries);
    }
    IdeStreamPairingException failure = null;
    for (Entry<P> entry : targets) {
      try {
        terminate(entry);
      } catch (IdeStreamPairingException error) {
        failure = failure == null ? error : failure;
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** 尚未交付 peer 的配对数（诊断/测试）。 */
  public int pendingCount() {
    synchronized (lock) {
      int count = 0;
      for (Entry<P> entry : byTicket.values()) {
        if (!entry.paired) {
          count++;
        }
      }
      return count;
    }
  }

  /** 已交付 peer、仍在转发的配对数（诊断/测试）。 */
  public int activeCount() {
    synchronized (lock) {
      int count = 0;
      for (Entry<P> entry : byTicket.values()) {
        if (entry.paired) {
          count++;
        }
      }
      return count;
    }
  }

  /** 终结一个配对：标记关闭、移出目录，关闭可能的 peer，并在未交付时执行 abandon 回调。 */
  private void terminate(Entry<P> entry) {
    P peer = null;
    Runnable abandon = null;
    synchronized (lock) {
      synchronized (entry) {
        if (entry.closed) {
          return;
        }
        entry.closed = true;
        removeLocked(entry);
        peer = entry.peer;
        if (!entry.paired) {
          abandon = entry.abandon;
        }
        entry.notifyAll();
      }
    }
    if (peer != null) {
      closePeer(peer);
    }
    if (abandon != null) {
      abandon.run();
    }
  }

  /** 调用方必须持有 {@code lock} 与 {@code entry} 监视器。 */
  private void removeLocked(Entry<P> entry) {
    byTicket.remove(entry.ticket, entry);
    Set<Entry<P>> entries = byGeneration.get(entry.generation);
    if (entries != null) {
      entries.remove(entry);
      if (entries.isEmpty()) {
        byGeneration.remove(entry.generation);
      }
    }
  }

  private static void closePeer(AutoCloseable peer) {
    try {
      peer.close();
    } catch (Exception error) {
      throw new IdeStreamPairingException("failed to close reverse stream peer", error);
    }
  }

  private static final class Entry<P extends AutoCloseable> {

    private final String ticket;
    private final DaemonConnectionIdentity generation;
    private final Runnable abandon;
    private final long deadlineNanos;
    private P peer;
    private boolean paired;
    private boolean closed;

    private Entry(
        String ticket, DaemonConnectionIdentity generation, Runnable abandon, Duration timeout) {
      this.ticket = ticket;
      this.generation = generation;
      this.abandon = abandon;
      this.deadlineNanos = System.nanoTime() + timeout.toNanos();
    }
  }

  /** 单个 pending 配对的句柄：等待 Daemon 反向连接并交付 peer，或显式关闭。 */
  public final class Pairing implements AutoCloseable {

    private final Entry<P> entry;

    private Pairing(Entry<P> entry) {
      this.entry = entry;
    }

    public String ticket() {
      return entry.ticket;
    }

    /**
     * 阻塞等待 Daemon 反向连接交付 peer。
     *
     * @throws IdeStreamPairingException 超时、配对在交付前被关闭，或等待被中断
     */
    public P awaitPeer() {
      synchronized (entry) {
        while (entry.peer == null && !entry.closed) {
          long remaining = entry.deadlineNanos - System.nanoTime();
          if (remaining <= 0L) {
            break;
          }
          try {
            entry.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IdeStreamPairingException("stream pairing wait was interrupted", error);
          }
        }
        if (entry.peer != null) {
          return entry.peer;
        }
      }
      terminate(entry);
      throw new IdeStreamPairingException("timed out waiting for the daemon reverse connection");
    }

    /** 关闭配对：未交付时执行 abandon 回调，已交付时关闭 peer（幂等）。 */
    @Override
    public void close() {
      terminate(entry);
    }
  }
}
