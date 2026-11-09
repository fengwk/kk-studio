package fun.fengwk.kkstudio.ide.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeBrowserUseGateway;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 意图：组合服务按 owner 快照决定本地/远端交付；本地不触远端，远端恰好一次内部调用，read 也遵循同一路由。 */
class ClusterEnvironmentIdeServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String BROWSER_SESSION = "66666666-7777-8888-9999-aaaaaaaaaaaa";
  private static final String EVENT_NODE = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
  private static final String CONNECTION = "12345678-1234-1234-1234-123456789abc";
  private static final String CLAIM = "87654321-4321-4321-4321-cba987654321";
  private static final String DAEMON_INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  private final StubRegistry registry = new StubRegistry();
  private final RecordingClient client = new RecordingClient();
  private final RecordingTransport localTransport = new RecordingTransport();
  private ClusterEnvironmentIdeService service;

  @BeforeEach
  void setUp() {
    EnvironmentIdeServiceImpl local =
        new EnvironmentIdeServiceImpl(
            localTransport,
            new IdeBootstrapTicketService(),
            new AllowGateway(),
            new IdeViewSnapshotStore(localTransport),
            Duration.ofSeconds(1));
    service = new ClusterEnvironmentIdeService(registry, client, local);
  }

  @Test
  void localOwnerDelegatesToTheLocalDelegate() {
    registry.current = Optional.of(ClusterOwnerRoute.local(NODE_A));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openViewRequest());

    assertEquals(VIEW, dto.getViewId());
    assertEquals(IdeAction.OPEN_VIEW, localTransport.commands.get(0).getAction());
    assertTrue(client.calls.isEmpty());
  }

  @Test
  void localBoundCommandsAndQueriesDoNotUseRemoteClient() {
    EnvironmentIdeServiceImpl delegate = mock(EnvironmentIdeServiceImpl.class);
    IdeClusterClient remote = mock(IdeClusterClient.class);
    service = new ClusterEnvironmentIdeService(registry, remote, delegate);
    registry.current = Optional.of(ClusterOwnerRoute.local(NODE_A));
    EnvironmentIdeChangeDirectoryDTO directory = new EnvironmentIdeChangeDirectoryDTO();
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    EnvironmentIdeReadViewDTO read = readViewRequest();

    service.changeDirectory(ENV, VIEW, directory);
    service.focusTerminal(ENV, VIEW, binding);
    service.collapseMobile(ENV, VIEW, binding);
    service.closeView(ENV, VIEW, close);
    service.listViews(ENV);
    service.listDirectories(ENV, "/tmp");
    service.readView(ENV, VIEW, read);

    verify(delegate).changeDirectory(ENV, VIEW, directory);
    verify(delegate).focusTerminal(ENV, VIEW, binding);
    verify(delegate).collapseMobile(ENV, VIEW, binding);
    verify(delegate).closeView(ENV, VIEW, close);
    verify(delegate).listViews(ENV);
    verify(delegate).listDirectories(ENV, "/tmp");
    verify(delegate).readView(ENV, VIEW, read);
    verifyNoInteractions(remote);
  }

  @Test
  void remoteOwnerUsesTheInternalClientExactlyOnce() {
    registry.current =
        Optional.of(ClusterOwnerRoute.remote(NODE_B, URI.create("http://b.internal")));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openViewRequest());

    assertSame(client.view, dto);
    assertEquals(List.of("openView"), client.calls);
    assertTrue(localTransport.commands.isEmpty());
  }

  @Test
  void remoteReadUsesTheInternalClientExactlyOnce() {
    registry.current =
        Optional.of(ClusterOwnerRoute.remote(NODE_B, URI.create("http://b.internal")));

    EnvironmentIdeViewSnapshotDTO snapshot = service.readView(ENV, VIEW, readViewRequest());

    assertSame(client.snapshot, snapshot);
    assertEquals(List.of("readView"), client.calls);
    assertTrue(localTransport.commands.isEmpty());
  }

  @Test
  void missingRouteFailsClosedBeforeAnyDelivery() {
    registry.current = Optional.empty();

    // 没有 owner 时确定未派发，reason 必须允许客户端等 READY 而不是把结果当作未知。
    DomainException error =
        assertThrows(DomainException.class, () -> service.openView(ENV, openViewRequest()));
    assertEquals("DAEMON_NOT_READY", error.reason());
    DomainException readError =
        assertThrows(DomainException.class, () -> service.readView(ENV, VIEW, readViewRequest()));
    assertEquals("DAEMON_NOT_READY", readError.reason());
    assertTrue(client.calls.isEmpty());
    assertTrue(localTransport.commands.isEmpty());
  }

  @Test
  void invalidViewIdIsRejectedBeforeRouting() {
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDirectory("/tmp");

    assertThrows(DomainException.class, () -> service.changeDirectory(ENV, "not-a-uuid", request));
    assertEquals(0, registry.calls);
    assertTrue(client.calls.isEmpty());
  }

  @Test
  void boundCommandsAndListRouteToClientWhenRemote() {
    registry.current =
        Optional.of(ClusterOwnerRoute.remote(NODE_B, URI.create("http://b.internal")));
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setBrowserSessionId(BROWSER_SESSION);
    binding.setBindingEpoch(3L);
    binding.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    close.setBrowserSessionId(BROWSER_SESSION);
    close.setBindingEpoch(3L);
    close.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    close.setDiscard(false);
    EnvironmentIdeChangeDirectoryDTO directory = new EnvironmentIdeChangeDirectoryDTO();
    directory.setBrowserSessionId(BROWSER_SESSION);
    directory.setBindingEpoch(3L);
    directory.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    directory.setDirectory("/tmp");

    service.changeDirectory(ENV, VIEW, directory);
    service.focusTerminal(ENV, VIEW, binding);
    service.collapseMobile(ENV, VIEW, binding);
    service.closeView(ENV, VIEW, close);
    service.listViews(ENV);
    service.readView(ENV, VIEW, readViewRequest());

    assertEquals(
        List.of(
            "changeDirectory",
            "focusTerminal",
            "collapseMobile",
            "closeView",
            "listViews",
            "readView"),
        client.calls);
  }

  private static EnvironmentIdeOpenViewDTO openViewRequest() {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setBrowserSessionId(BROWSER_SESSION);
    request.setEventNodeId(EVENT_NODE);
    request.setConnectionId(CONNECTION);
    request.setClaimId(CLAIM);
    request.setMode(EnvironmentIdeOpenMode.CREATE);
    request.setExpectedBindingEpoch(0L);
    request.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    request.setDirectory("/home/dev/project");
    return request;
  }

  private static EnvironmentIdeReadViewDTO readViewRequest() {
    EnvironmentIdeReadViewDTO request = new EnvironmentIdeReadViewDTO();
    request.setBrowserSessionId(BROWSER_SESSION);
    request.setEventNodeId(EVENT_NODE);
    request.setConnectionId(CONNECTION);
    request.setClaimId(CLAIM);
    request.setExpectedDaemonInstanceId(DAEMON_INSTANCE);
    return request;
  }

  private static final class StubRegistry implements ClusterNodeRegistry {

    private Optional<ClusterOwnerRoute> current = Optional.empty();
    private int calls;

    @Override
    public Optional<ClusterOwnerRoute> runtimeRoute(EnvironmentId environmentId) {
      return current;
    }

    @Override
    public Optional<ClusterOwnerRoute> route(EnvironmentId environmentId) {
      calls++;
      return current;
    }

    @Override
    public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
      return Optional.empty();
    }
  }

  private static final class RecordingClient extends IdeClusterClient {

    private final List<String> calls = new ArrayList<>();
    private final EnvironmentIdeViewDTO view = new EnvironmentIdeViewDTO();
    private final EnvironmentIdeViewSnapshotDTO snapshot = new EnvironmentIdeViewSnapshotDTO();

    RecordingClient() {
      super(
          HttpClient.newHttpClient(),
          JsonMapper.builder().build(),
          new HmacClusterInternalAuthentication(
              SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 64));
      view.setViewId(VIEW);
      view.setEnvironmentId(ENV.toString());
      view.setDirectory("/home/dev/project");
      snapshot.setDaemonInstanceId(DAEMON_INSTANCE);
      snapshot.setRevision(4L);
    }

    @Override
    public EnvironmentIdeViewDTO openView(
        EnvironmentId environmentId, ClusterOwnerRoute owner, EnvironmentIdeOpenViewDTO request) {
      calls.add("openView");
      return view;
    }

    @Override
    public EnvironmentIdeViewDTO changeDirectory(
        EnvironmentId environmentId,
        ClusterOwnerRoute owner,
        String viewId,
        EnvironmentIdeChangeDirectoryDTO request) {
      calls.add("changeDirectory");
      return view;
    }

    @Override
    public void focusTerminal(
        EnvironmentId environmentId,
        ClusterOwnerRoute owner,
        String viewId,
        EnvironmentIdeViewBindingDTO request) {
      calls.add("focusTerminal");
    }

    @Override
    public void closeView(
        EnvironmentId environmentId,
        ClusterOwnerRoute owner,
        String viewId,
        EnvironmentIdeCloseViewDTO request) {
      calls.add("closeView");
    }

    @Override
    public void collapseMobile(
        EnvironmentId environmentId,
        ClusterOwnerRoute owner,
        String viewId,
        EnvironmentIdeViewBindingDTO request) {
      calls.add("collapseMobile");
    }

    @Override
    public EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId, ClusterOwnerRoute owner) {
      calls.add("listViews");
      return new EnvironmentIdeViewsDTO();
    }

    @Override
    public EnvironmentIdeViewSnapshotDTO readView(
        EnvironmentId environmentId,
        ClusterOwnerRoute owner,
        String viewId,
        EnvironmentIdeReadViewDTO request) {
      calls.add("readView");
      return snapshot;
    }
  }

  private static final class RecordingTransport extends IdeCommandTransport {

    private RecordingTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    private final List<IdeCommand> commands = new ArrayList<>();

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeResult> reply = new CompletableFuture<>();
      IdeResult.Builder builder =
          IdeResult.newBuilder()
              .setOk(true)
              .setRevision(1L)
              .addViews(
                  IdeViewSummary.newBuilder()
                      .setViewId(command.getViewId())
                      .setBrowserSessionId(command.getBrowserSessionId())
                      .setEventNodeId(command.getEventNodeId())
                      .setConnectionId(command.getConnectionId())
                      .setClaimId(command.getClaimId())
                      .setBindingEpoch(1L)
                      .setDirectory("/home/dev/project")
                      .setWorkspacePath("/daemon/workspaces/" + command.getViewId())
                      .setState(IdeViewState.OPEN));
      reply.complete(builder.build());
      return new IdeRequest(
          new DaemonConnectionIdentity(environmentId, DAEMON_INSTANCE), UUID.randomUUID(), reply);
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(new DaemonConnectionIdentity(environmentId, DAEMON_INSTANCE));
    }

    @Override
    public void sendIdeLease(
        EnvironmentId environmentId,
        DaemonConnectionIdentity expectedIdentity,
        DaemonIdeLease lease) {
      throw new AssertionError("unexpected lease");
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }

  private static final class AllowGateway extends IdeBrowserUseGateway {

    private AllowGateway() {
      super(mock(IdeBrowserUseClient.class));
    }

    @Override
    public boolean findExactIntent(
        UUID eventNodeId,
        UUID connectionId,
        UUID browserSessionId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId) {
      return true;
    }

    @Override
    public Optional<Proofs> activeBoundProofs(
        UUID eventNodeId, UUID connectionId, UUID browserSessionId) {
      return Optional.empty();
    }
  }
}
