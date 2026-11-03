package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 环境在途能力调用与已终结 tombstone 跟踪器：管理调用注册、终态原子迁移、LRU 墓碑淘汰与退出确认匹配。 */
final class DaemonInvocationTracker {

  static final int MAX_INVOCATION_TOMBSTONES = 1024;

  private final Object lock = new Object();
  private final Map<EnvironmentId, LinkedHashMap<UUID, ActiveInvocation>> invocationsByEnvironment =
      new HashMap<>();
  private final Map<EnvironmentId, LinkedHashMap<UUID, ExitConfirmation>> invocationTombstones =
      new HashMap<>();

  void register(ActiveInvocation active) {
    synchronized (lock) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.computeIfAbsent(
              active.environmentId, ignored -> new LinkedHashMap<>());
      if (invocations.containsKey(active.invocationId)
          || hasTombstoneLocked(active.environmentId, active.invocationId)) {
        throw new IllegalArgumentException(
            "invocationId is already active for environment "
                + active.environmentId
                + ": "
                + active.invocationId);
      }
      invocations.put(active.invocationId, active);
    }
  }

  /**
   * 挂接在途能力调用：如果已有在途登记抛 {@link IllegalArgumentException}； 如果已有 tombstone 则允许替换（删除该 tombstone
   * 再登记新的在途记录）。
   */
  void registerAttach(ActiveInvocation active) {
    synchronized (lock) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.computeIfAbsent(
              active.environmentId, ignored -> new LinkedHashMap<>());
      if (invocations.containsKey(active.invocationId)) {
        throw new IllegalArgumentException(
            "invocationId is already active for environment "
                + active.environmentId
                + ": "
                + active.invocationId);
      }
      removeTombstoneLocked(active.environmentId, active.invocationId);
      invocations.put(active.invocationId, active);
    }
  }

  private void removeTombstoneLocked(EnvironmentId environmentId, UUID invocationId) {
    LinkedHashMap<UUID, ExitConfirmation> tombstones = invocationTombstones.get(environmentId);
    if (tombstones != null) {
      tombstones.remove(invocationId);
      if (tombstones.isEmpty()) {
        invocationTombstones.remove(environmentId);
      }
    }
  }

  boolean unregister(ActiveInvocation active) {
    synchronized (lock) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.get(active.environmentId);
      if (invocations == null || invocations.remove(active.invocationId) == null) {
        return false;
      }
      if (invocations.isEmpty()) {
        invocationsByEnvironment.remove(active.environmentId);
      }
      return true;
    }
  }

  /**
   * 调用方放弃一个在途调用：在同一把锁内注销、标记终态并写入 tombstone。
   *
   * <p>原子迁移保证与之竞争的远端终态帧要么先拿到调用并成为唯一终态，要么看到 tombstone 而被忽略，不会被判为未知调用。
   *
   * @return 本次调用完成了迁移时为 {@code true}；调用已由其他路径终结时为 {@code false}
   */
  boolean retire(ActiveInvocation active) {
    synchronized (lock) {
      if (takeRegisteredLocked(active.environmentId, active.invocationId) == null) {
        return false;
      }
      addTombstoneLocked(active);
      return true;
    }
  }

  private ActiveInvocation takeRegisteredLocked(EnvironmentId environmentId, UUID invocationId) {
    LinkedHashMap<UUID, ActiveInvocation> invocations = invocationsByEnvironment.get(environmentId);
    if (invocations == null) {
      return null;
    }
    ActiveInvocation active = invocations.remove(invocationId);
    if (active == null) {
      return null;
    }
    if (invocations.isEmpty()) {
      invocationsByEnvironment.remove(environmentId);
    }
    active.markTerminal();
    return active;
  }

  ActiveInvocation callbackTarget(EnvironmentId environmentId, UUID invocationId) {
    synchronized (lock) {
      ActiveInvocation active = registeredInvocationLocked(environmentId, invocationId);
      if (active != null) {
        return active;
      }
      return null;
    }
  }

  ActiveInvocation takeTerminalTarget(EnvironmentId environmentId, UUID invocationId) {
    synchronized (lock) {
      ActiveInvocation active = takeRegisteredLocked(environmentId, invocationId);
      if (active != null) {
        addTombstoneLocked(active);
        return active;
      }
      return null;
    }
  }

  private void addTombstoneLocked(ActiveInvocation active) {
    LinkedHashMap<UUID, ExitConfirmation> tombstones =
        invocationTombstones.computeIfAbsent(
            active.environmentId, ignored -> new LinkedHashMap<>());
    ExitConfirmation retained = new ExitConfirmation(active.listener, active.daemonInstanceId);
    if (active.exitConfirmed.get()) {
      retained.delivered.set(true);
    }
    tombstones.put(active.invocationId, retained);
    while (tombstones.size() > MAX_INVOCATION_TOMBSTONES) {
      evictOneTombstoneLocked(tombstones);
    }
  }

  private static void evictOneTombstoneLocked(LinkedHashMap<UUID, ExitConfirmation> tombstones) {
    Iterator<ExitConfirmation> retained = tombstones.values().iterator();
    while (retained.hasNext()) {
      if (retained.next().delivered.get()) {
        retained.remove();
        return;
      }
    }
    Iterator<UUID> oldest = tombstones.keySet().iterator();
    if (oldest.hasNext()) {
      oldest.next();
      oldest.remove();
    }
  }

  ActiveInvocation registeredInvocation(EnvironmentId environmentId, UUID invocationId) {
    synchronized (lock) {
      return registeredInvocationLocked(environmentId, invocationId);
    }
  }

  private ActiveInvocation registeredInvocationLocked(
      EnvironmentId environmentId, UUID invocationId) {
    LinkedHashMap<UUID, ActiveInvocation> invocations = invocationsByEnvironment.get(environmentId);
    return invocations == null ? null : invocations.get(invocationId);
  }

  boolean hasTombstone(EnvironmentId environmentId, UUID invocationId) {
    synchronized (lock) {
      return hasTombstoneLocked(environmentId, invocationId);
    }
  }

  private boolean hasTombstoneLocked(EnvironmentId environmentId, UUID invocationId) {
    LinkedHashMap<UUID, ExitConfirmation> tombstones = invocationTombstones.get(environmentId);
    return tombstones != null && tombstones.containsKey(invocationId);
  }

  List<ActiveInvocation> activeInvocations(EnvironmentId environmentId) {
    synchronized (lock) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.get(environmentId);
      return invocations == null ? List.of() : List.copyOf(invocations.values());
    }
  }

  int activeInvocationCount(EnvironmentId environmentId) {
    synchronized (lock) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.get(environmentId);
      return invocations == null ? 0 : invocations.size();
    }
  }

  List<ActiveInvocation> takeAbandonedInvocations(EnvironmentId environmentId) {
    List<ActiveInvocation> abandoned = new ArrayList<>();
    synchronized (lock) {
      LinkedHashMap<UUID, ActiveInvocation> invocations =
          invocationsByEnvironment.remove(environmentId);
      if (invocations != null) {
        for (ActiveInvocation active : invocations.values()) {
          active.markTerminal();
          addTombstoneLocked(active);
          abandoned.add(active);
        }
      }
    }
    return abandoned;
  }

  Optional<ExitConfirmation> claimExitConfirmation(
      EnvironmentId environmentId, UUID invocationId, String daemonInstanceId) {
    synchronized (lock) {
      ActiveInvocation active = registeredInvocationLocked(environmentId, invocationId);
      if (active != null) {
        if (!active.daemonInstanceId.equals(daemonInstanceId)) {
          return Optional.empty();
        }
        if (active.claimExit()) {
          return Optional.of(new ExitConfirmation(active.listener, daemonInstanceId, true));
        }
        return Optional.empty();
      }
      LinkedHashMap<UUID, ExitConfirmation> tombstones = invocationTombstones.get(environmentId);
      if (tombstones != null) {
        ExitConfirmation confirmation = tombstones.get(invocationId);
        if (confirmation != null
            && confirmation.daemonInstanceId.equals(daemonInstanceId)
            && confirmation.claim()) {
          return Optional.of(confirmation);
        }
      }
      return Optional.empty();
    }
  }

  boolean isKnown(EnvironmentId environmentId, UUID invocationId) {
    synchronized (lock) {
      return registeredInvocationLocked(environmentId, invocationId) != null
          || hasTombstoneLocked(environmentId, invocationId);
    }
  }

  List<UUID> pendingExitConfirmations(EnvironmentId environmentId, String daemonInstanceId) {
    List<UUID> pending = new ArrayList<>();
    synchronized (lock) {
      LinkedHashMap<UUID, ExitConfirmation> tombstones = invocationTombstones.get(environmentId);
      if (tombstones == null) {
        return List.of();
      }
      for (Map.Entry<UUID, ExitConfirmation> entry : tombstones.entrySet()) {
        ExitConfirmation confirmation = entry.getValue();
        if (!confirmation.delivered.get()
            && confirmation.daemonInstanceId.equals(daemonInstanceId)) {
          pending.add(entry.getKey());
        }
      }
    }
    return pending;
  }

  boolean isActive(ActiveInvocation active) {
    if (active.isTerminal()) {
      return false;
    }
    return registeredInvocation(active.environmentId, active.invocationId) == active;
  }
}
