package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 意图：写命令发出前必须同时具备 READY 实例、事件节点 intent 与快照内 owner 绑定，任何一项缺失都失败关闭。 */
class IdeBindingsTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID BROWSER_SESSION =
      UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID OTHER_SESSION = UUID.fromString("99999999-9999-9999-9999-999999999999");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID USER = new UUID(0, 1);
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");

  private StubTransport transport;
  private StubBrowserUse browserUse;
  private IdeViewSnapshotStore store;
  private IdeBindings bindings;

  @BeforeEach
  void setUp() {
    transport = new StubTransport();
    browserUse = new StubBrowserUse();
    store = new IdeViewSnapshotStore(transport);
    IdeSnapshots snapshots =
        new IdeSnapshots(
            new IdeCommandCaller(transport, Duration.ofMillis(200)),
            transport,
            store,
            new IdeBootstrapTicketService());
    bindings = new IdeBindings(transport, browserUse, snapshots);
  }

  @Test
  void requireReadyRejectsWhenNoInstanceIsReady() {
    transport.identity = null;

    DomainException error = assertThrows(DomainException.class, () -> bindings.requireReady(ENV));

    assertEquals("DAEMON_NOT_READY", error.reason());
    assertEquals("environment daemon is not ready", error.getMessage());
  }

  @Test
  void requireIntentAcceptsOnlyAnExactlyConfirmedIntent() {
    bindings.requireIntent(EVENT_NODE, CONNECTION, BROWSER_SESSION, ENV, VIEW, CLAIM, USER);
    assertEquals(1, browserUse.queries);

    browserUse.matched = false;
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireIntent(
                    EVENT_NODE, CONNECTION, BROWSER_SESSION, ENV, VIEW, CLAIM, USER));
    assertEquals("STALE_BINDING", error.reason());
    assertEquals("browser IDE usage intent is no longer confirmed", error.getMessage());
  }

  @Test
  void unavailableIntentStaysUnavailable() {
    browserUse.failure = DomainException.unavailable("IDE_UNAVAILABLE", "node down");
    DomainException passthrough =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireIntent(
                    EVENT_NODE, CONNECTION, BROWSER_SESSION, ENV, VIEW, CLAIM, USER));
    assertEquals("IDE_UNAVAILABLE", passthrough.reason());
    assertEquals("node down", passthrough.getMessage());

    browserUse.failure = DomainException.validation("browserUse", "bad");
    DomainException wrapped =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireIntent(
                    EVENT_NODE, CONNECTION, BROWSER_SESSION, ENV, VIEW, CLAIM, USER));
    assertEquals("IDE_UNAVAILABLE", wrapped.reason());
    assertEquals("browser IDE usage intent is unavailable", wrapped.getMessage());
  }

  @Test
  void unexpectedIntentFailureIsWrappedAsUnavailable() {
    browserUse.runtimeFailure = new IllegalStateException("boom");

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireIntent(
                    EVENT_NODE, CONNECTION, BROWSER_SESSION, ENV, VIEW, CLAIM, USER));

    assertEquals("IDE_UNAVAILABLE", error.reason());
    assertEquals("browser IDE usage intent is unavailable", error.getMessage());
    assertEquals(browserUse.runtimeFailure, error.getCause());
  }

  @Test
  void requireBindingReadsTheCacheFirstAndRefreshesOnlyWhenMissing() {
    store.accept(IDENTITY, 1L, List.of(snapshotView(7L, BROWSER_SESSION)));

    IdeBindings.Binding binding =
        bindings.requireBinding(ENV, VIEW, BROWSER_SESSION, 7L, IDENTITY.daemonInstanceId(), USER);

    assertEquals(IDENTITY, binding.identity());
    assertEquals(VIEW, binding.view().viewId());
    assertTrue(transport.commands.isEmpty());
    assertEquals(1, browserUse.queries);
  }

  @Test
  void requireBindingRefreshesWhenTheViewIsNotCachedYet() {
    transport.replies.add(snapshot(2L, view(7L, BROWSER_SESSION)));

    IdeBindings.Binding binding =
        bindings.requireBinding(ENV, VIEW, BROWSER_SESSION, 7L, IDENTITY.daemonInstanceId(), USER);

    assertEquals(7L, binding.view().bindingEpoch());
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.get(0).getAction());
  }

  @Test
  void requireBindingRejectsMissingViewForeignSessionAndStaleEpoch() {
    store.accept(IDENTITY, 1L, List.of(snapshotView(7L, BROWSER_SESSION)));
    // 缓存没有目标 view 时会先读一次 Daemon 快照；刷新后仍缺失才算 view 消失。
    transport.replies.add(snapshot(2L, view(7L, BROWSER_SESSION)));

    DomainException missing =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireBinding(
                    ENV,
                    UUID.randomUUID(),
                    BROWSER_SESSION,
                    7L,
                    IDENTITY.daemonInstanceId(),
                    USER));
    assertEquals("VIEW_GONE", missing.reason());

    DomainException foreign =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireBinding(
                    ENV, VIEW, OTHER_SESSION, 7L, IDENTITY.daemonInstanceId(), USER));
    assertEquals("VIEW_OWNED_BY_OTHER_SESSION", foreign.reason());

    DomainException stale =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireBinding(
                    ENV, VIEW, BROWSER_SESSION, 6L, IDENTITY.daemonInstanceId(), USER));
    assertEquals("STALE_BINDING", stale.reason());
    assertEquals("IDE view binding has been replaced", stale.getMessage());
  }

  @Test
  void requireBindingRejectsAnExpectedInstanceThatNoLongerMatches() {
    store.accept(IDENTITY, 1L, List.of(snapshotView(7L, BROWSER_SESSION)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                bindings.requireBinding(
                    ENV, VIEW, BROWSER_SESSION, 7L, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", USER));
    assertEquals("VIEW_GONE", error.reason());
    assertEquals("environment daemon instance has been replaced", error.getMessage());
  }

  private static IdeViewSnapshotStore.View snapshotView(long epoch, UUID browserSessionId) {
    IdeViewSummary summary = view(epoch, browserSessionId);
    return new IdeViewSnapshotStore.View(
        VIEW,
        BROWSER_SESSION,
        EVENT_NODE,
        CONNECTION,
        CLAIM,
        epoch,
        summary.getDirectory(),
        summary.getWorkspacePath(),
        IdeViewState.OPEN);
  }

  private static IdeViewSummary view(long epoch, UUID browserSessionId) {
    return IdeViewSummary.newBuilder()
        .setViewId(VIEW.toString())
        .setBrowserSessionId(browserSessionId.toString())
        .setEventNodeId(EVENT_NODE.toString())
        .setConnectionId(CONNECTION.toString())
        .setClaimId(CLAIM.toString())
        .setBindingEpoch(epoch)
        .setDirectory("/dir")
        .setWorkspacePath("/ws")
        .setState(IdeViewState.OPEN)
        .build();
  }

  private static IdeResult snapshot(long revision, IdeViewSummary... views) {
    return IdeResult.newBuilder()
        .setOk(true)
        .setRevision(revision)
        .addAllViews(List.of(views))
        .build();
  }

  private static final class StubBrowserUse extends IdeBrowserUseGateway {

    private boolean matched = true;
    private DomainException failure;
    private RuntimeException runtimeFailure;
    private int queries;

    private StubBrowserUse() {
      super(mock(IdeBrowserUseClient.class));
    }

    @Override
    public boolean findExactIntent(
        UUID eventNodeId,
        UUID connectionId,
        UUID browserSessionId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId,
        UUID userId) {
      queries++;
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      if (failure != null) {
        throw failure;
      }
      return matched;
    }
  }

  private static final class StubTransport extends IdeCommandTransport {

    private final List<IdeResult> replies = new ArrayList<>();
    private final List<IdeCommand> commands = new ArrayList<>();
    private volatile DaemonConnectionIdentity identity = IDENTITY;

    private StubTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      return new IdeRequest(
          IDENTITY, UUID.randomUUID(), CompletableFuture.completedFuture(replies.remove(0)));
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.ofNullable(identity);
    }
  }
}
