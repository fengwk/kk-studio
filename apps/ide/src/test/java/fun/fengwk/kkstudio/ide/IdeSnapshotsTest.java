package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 意图：快照只在实例/绑定都匹配时写入；缺失 revision 或绑定漂移时不污染缓存，票据按精确 epoch 撤销。 */
class IdeSnapshotsTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID BROWSER_SESSION =
      UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");

  private StubTransport transport;
  private IdeBootstrapTicketService tickets;
  private IdeViewSnapshotStore store;
  private IdeSnapshots snapshots;

  @BeforeEach
  void setUp() {
    transport = new StubTransport();
    tickets = new IdeBootstrapTicketService();
    store = new IdeViewSnapshotStore(transport);
    snapshots =
        new IdeSnapshots(
            new IdeCommandCaller(transport, Duration.ofMillis(200)), transport, store, tickets);
  }

  @Test
  void refreshReadsTheDaemonSnapshotAndCachesIt() {
    transport.replies.add(snapshot(5L, summary(7L, "/dir")));

    IdeViewSnapshotStore.Snapshot snapshot = snapshots.refresh(ENV, IDENTITY);

    assertEquals(5L, snapshot.revision());
    assertTrue(store.snapshot(IDENTITY).orElseThrow().find(VIEW).isPresent());
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.get(0).getAction());
  }

  @Test
  void refreshRejectsAReplacedInstanceWithoutWriting() {
    transport.replies.add(snapshot(5L, summary(7L, "/dir")));
    transport.identity = new DaemonConnectionIdentity(ENV, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    DomainException error =
        assertThrows(DomainException.class, () -> snapshots.refresh(ENV, IDENTITY));

    assertEquals("STALE_BINDING", error.reason());
    assertEquals("IDE snapshot belongs to a replaced daemon instance", error.getMessage());
    assertTrue(store.snapshot(IDENTITY).isEmpty());
  }

  @Test
  void refreshAcceptsTheFailureReplySnapshotBeforeRethrowing() {
    transport.replies.add(
        IdeResult.newBuilder()
            .setOk(false)
            .setCode("BUSY")
            .setRevision(3L)
            .addAllViews(List.of(summary(7L, "/dir")))
            .build());

    DomainException error =
        assertThrows(DomainException.class, () -> snapshots.refresh(ENV, IDENTITY));

    assertEquals("IDE_UNAVAILABLE", error.reason());
    assertEquals(3L, store.snapshot(IDENTITY).orElseThrow().revision());
  }

  @Test
  void replyWithoutRevisionIsAProtocolError() {
    IdeCall call = new IdeCall(IdeResult.newBuilder().setOk(true).build(), transport.identity);

    DomainException error =
        assertThrows(DomainException.class, () -> snapshots.requireReplySnapshot(call));

    assertEquals("IDE_PROTOCOL_ERROR", error.reason());
    assertEquals("daemon did not return a complete IDE snapshot", error.getMessage());
  }

  @Test
  void bindingReplyMustKeepEveryStableField() {
    IdeViewSnapshotStore.View expected = view(7L, "/dir");
    transport.replies.add(snapshot(4L, summary(7L, "/dir")));
    IdeCall matching = new IdeCall(transport.replies.peekFirst(), transport.identity);

    assertEquals(4L, snapshots.requireReplySnapshotForBinding(matching, expected).revision());

    IdeCall drifted = new IdeCall(snapshot(5L, summary(8L, "/dir")), transport.identity);
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> snapshots.requireReplySnapshotForBinding(drifted, expected));
    assertEquals("STALE_BINDING", error.reason());
    assertEquals("IDE command reply contains a different binding", error.getMessage());
  }

  @Test
  void optionalSnapshotIgnoresRepliesWithoutRevision() {
    IdeViewSnapshotStore.View expected = view(7L, "/dir");
    IdeCall call = new IdeCall(IdeResult.newBuilder().setOk(true).build(), transport.identity);

    snapshots.acceptOptionalSnapshot(call, expected);

    assertTrue(store.snapshot(IDENTITY).isEmpty());
  }

  @Test
  void optionalSnapshotRejectsDriftedBinding() {
    IdeViewSnapshotStore.View expected = view(7L, "/dir");
    IdeCall call =
        new IdeCall(
            IdeResult.newBuilder()
                .setOk(true)
                .setRevision(1L)
                .addAllViews(List.of(summary(8L, "/dir")))
                .build(),
            transport.identity);

    DomainException error =
        assertThrows(DomainException.class, () -> snapshots.acceptOptionalSnapshot(call, expected));
    assertEquals("IDE command reply contains a different binding", error.getMessage());
  }

  @Test
  void replacedEpochTicketIsRevokedWhileTheCurrentEpochTicketSurvives() {
    tickets.issue(IDENTITY, VIEW.toString(), 7L, "/ws");
    tickets.issue(IDENTITY, VIEW.toString(), 8L, "/ws");
    assertEquals(2, tickets.pendingCount());

    transport.replies.add(snapshot(1L, summary(7L, "/dir")));
    snapshots.refresh(ENV, IDENTITY);
    transport.replies.add(snapshot(2L, summary(8L, "/dir")));
    snapshots.refresh(ENV, IDENTITY);

    // 只有被替换的旧 epoch 被撤销；已接受的当前 epoch 票据不被更早快照的清理删除。
    assertEquals(1, tickets.pendingCount());
  }

  @Test
  void cachedViewOnlyReadsTheStore() {
    assertTrue(snapshots.cachedView(IDENTITY, VIEW).isEmpty());
    assertTrue(transport.commands.isEmpty());
  }

  @Test
  void currentGenerationRequiresTheSameReadyInstance() {
    snapshots.requireCurrentGeneration(IDENTITY);

    transport.identity = new DaemonConnectionIdentity(ENV, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    DomainException error =
        assertThrows(DomainException.class, () -> snapshots.requireCurrentGeneration(IDENTITY));
    assertEquals("IDE result belongs to a replaced daemon instance", error.getMessage());
  }

  @Test
  void sameBindingIgnoresDirectoryButNotEpochOrWorkspaceFile() {
    IdeViewSnapshotStore.View expected = view(7L, "/dir");
    assertTrue(IdeSnapshots.sameBinding(view(7L, "/other"), expected));
    assertTrue(
        IdeSnapshots.sameBinding(view(7L, null), expected),
        "a zero-root rebinding keeps the same owner binding identity");
    assertEquals(false, IdeSnapshots.sameBinding(view(8L, "/dir"), expected));
    assertEquals(
        false,
        IdeSnapshots.sameBinding(view(7L, "/dir", "/other.code-workspace"), expected),
        "the registered workspace file is part of the binding identity");
  }

  @Test
  void missingReplySnapshotLeavesTheCacheEmpty() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                snapshots.requireReplySnapshot(
                    new IdeCall(IdeResult.newBuilder().setOk(true).build(), transport.identity)));
    assertNull(store.snapshot(IDENTITY).orElse(null));
    assertEquals("IDE_PROTOCOL_ERROR", error.reason());
  }

  private static IdeResult snapshot(long revision, IdeViewSummary... views) {
    return IdeResult.newBuilder()
        .setOk(true)
        .setRevision(revision)
        .addAllViews(List.of(views))
        .build();
  }

  private static IdeViewSummary summary(long epoch, String directory) {
    return IdeViewSummary.newBuilder()
        .setViewId(VIEW.toString())
        .setBrowserSessionId(BROWSER_SESSION.toString())
        .setEventNodeId(EVENT_NODE.toString())
        .setConnectionId(CONNECTION.toString())
        .setClaimId(CLAIM.toString())
        .setBindingEpoch(epoch)
        .setDirectory(directory)
        .setWorkspacePath("/ws")
        .setState(IdeViewState.OPEN)
        .build();
  }

  private static IdeViewSnapshotStore.View view(long epoch, String directory) {
    return view(epoch, directory, "/ws");
  }

  private static IdeViewSnapshotStore.View view(
      long epoch, String directory, String workspacePath) {
    return new IdeViewSnapshotStore.View(
        VIEW,
        BROWSER_SESSION,
        EVENT_NODE,
        CONNECTION,
        CLAIM,
        epoch,
        directory,
        workspacePath,
        IdeViewState.OPEN);
  }

  private static final class StubTransport extends IdeCommandTransport {

    private final Deque<IdeResult> replies = new ArrayDeque<>();
    private final List<IdeCommand> commands = new ArrayList<>();
    private DaemonConnectionIdentity identity = IDENTITY;

    private StubTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeResult> reply = CompletableFuture.completedFuture(replies.removeFirst());
      return new IdeRequest(identity, UUID.randomUUID(), reply);
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(identity);
    }
  }
}
