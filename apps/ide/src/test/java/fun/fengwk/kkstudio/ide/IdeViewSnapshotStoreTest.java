package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 意图：唯一权威快照只接受单调 revision，同 revision 内容漂移是协议错误，旧实例快照不能删除新代际绑定。 */
class IdeViewSnapshotStoreTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID BROWSER_SESSION =
      UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");
  private static final DaemonConnectionIdentity CURRENT =
      new DaemonConnectionIdentity(ENV, "daemon-current");
  private static final DaemonConnectionIdentity REPLACED =
      new DaemonConnectionIdentity(ENV, "daemon-replaced");

  @Test
  void acceptsHigherRevisionAndNeverRegressesOnLateLowerRevision() {
    IdeViewSnapshotStore store = new IdeViewSnapshotStore(environmentId -> Optional.of(CURRENT));

    IdeViewSnapshotStore.Acceptance first = store.accept(CURRENT, 1L, List.of());
    assertEquals(IdeViewSnapshotStore.Status.ACCEPTED, first.status());

    IdeViewSnapshotStore.Acceptance second =
        store.accept(CURRENT, 2L, List.of(view(7L, "/workspace")));
    assertEquals(IdeViewSnapshotStore.Status.ACCEPTED, second.status());
    assertTrue(second.snapshot().find(VIEW).isPresent());

    IdeViewSnapshotStore.Acceptance late = store.accept(CURRENT, 1L, List.of());
    assertEquals(IdeViewSnapshotStore.Status.STALE, late.status());
    assertTrue(late.snapshot().find(VIEW).isPresent(), "旧快照不得删除新绑定");
    assertEquals(2L, store.snapshot(CURRENT).orElseThrow().revision());
  }

  @Test
  void sameRevisionRequiresIdenticalContent() {
    IdeViewSnapshotStore store = new IdeViewSnapshotStore(environmentId -> Optional.of(CURRENT));
    store.accept(CURRENT, 2L, List.of(view(7L, "/workspace")));

    IdeViewSnapshotStore.Acceptance unchanged =
        store.accept(CURRENT, 2L, List.of(view(7L, "/workspace")));
    assertEquals(IdeViewSnapshotStore.Status.UNCHANGED, unchanged.status());

    assertThrows(
        DomainException.class, () -> store.accept(CURRENT, 2L, List.of(view(8L, "/workspace"))));
  }

  @Test
  void newDaemonGenerationStartsAFreshRevisionScopeAndDiscardsOldGeneration() {
    AtomicReference<DaemonConnectionIdentity> current = new AtomicReference<>(CURRENT);
    IdeViewSnapshotStore store =
        new IdeViewSnapshotStore(environmentId -> Optional.of(current.get()));
    store.accept(CURRENT, 9L, List.of(view(7L, "/workspace")));

    current.set(REPLACED);
    IdeViewSnapshotStore.Acceptance replacement =
        store.accept(REPLACED, 1L, List.of(view(8L, "/other")));

    assertEquals(IdeViewSnapshotStore.Status.ACCEPTED, replacement.status());
    assertTrue(store.snapshot(CURRENT).isEmpty(), "新进程不得保留旧代际快照");
    assertEquals(1L, store.snapshot(REPLACED).orElseThrow().revision());
  }

  @Test
  void staleGenerationAcceptedSnapshotIsRejectedBeforeWriting() {
    IdeViewSnapshotStore store = new IdeViewSnapshotStore(environmentId -> Optional.of(CURRENT));
    store.accept(CURRENT, 1L, List.of(view(7L, "/workspace")));

    assertThrows(DomainException.class, () -> store.accept(REPLACED, 2L, List.of()));
    assertFalse(store.snapshot(CURRENT).isEmpty());
  }

  private static IdeViewSnapshotStore.View view(long bindingEpoch, String workspacePath) {
    return new IdeViewSnapshotStore.View(
        VIEW,
        BROWSER_SESSION,
        EVENT_NODE,
        CONNECTION,
        CLAIM,
        bindingEpoch,
        "/dir",
        workspacePath,
        IdeViewState.OPEN);
  }
}
