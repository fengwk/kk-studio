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

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeBrowserUseGateway;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;
import fun.fengwk.kkstudio.ide.IdeViewBindingRegistry;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.error.DomainException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 意图：组合服务按 owner 快照决定本地/远端交付——本地直接委托且不触碰内部客户端，远端恰好一次内部调用；无路由时在交付前 fail-closed， 非法 viewId
 * 在路由查询前拒绝，所有带文档命令都保留 document+epoch。
 */
class ClusterEnvironmentIdeServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String DOCUMENT = "66666666-7777-8888-9999-aaaaaaaaaaaa";
  private static final String EVENT_NODE = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
  private static final String CONNECTION = "12345678-1234-1234-1234-123456789abc";
  private static final String CLAIM = "87654321-4321-4321-4321-cba987654321";
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
            new IdeViewBindingRegistry(),
            Duration.ofSeconds(1),
            new NoopAuthorization());
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

  /** 本地 owner 的各类视图命令只交给本地服务，不触发任何远端请求。 */
  @Test
  void localBoundCommandsAndQueriesDoNotUseRemoteClient() {
    EnvironmentIdeServiceImpl delegate = mock(EnvironmentIdeServiceImpl.class);
    IdeClusterClient remote = mock(IdeClusterClient.class);
    service = new ClusterEnvironmentIdeService(registry, remote, delegate);
    registry.current = Optional.of(ClusterOwnerRoute.local(NODE_A));
    EnvironmentIdeChangeDirectoryDTO directory = new EnvironmentIdeChangeDirectoryDTO();
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();

    service.changeDirectory(ENV, VIEW, directory);
    service.focusTerminal(ENV, VIEW, binding);
    service.collapseMobile(ENV, VIEW, binding);
    service.closeView(ENV, VIEW, close);
    service.listViews(ENV);
    service.listDirectories(ENV, "/tmp");

    verify(delegate).changeDirectory(ENV, VIEW, directory);
    verify(delegate).focusTerminal(ENV, VIEW, binding);
    verify(delegate).collapseMobile(ENV, VIEW, binding);
    verify(delegate).closeView(ENV, VIEW, close);
    verify(delegate).listViews(ENV);
    verify(delegate).listDirectories(ENV, "/tmp");
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
  void missingRouteFailsClosedBeforeAnyDelivery() {
    registry.current = Optional.empty();

    assertThrows(DomainException.class, () -> service.openView(ENV, openViewRequest()));
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
    binding.setDocumentId(DOCUMENT);
    binding.setBindingEpoch("3");
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    close.setDocumentId(DOCUMENT);
    close.setBindingEpoch("3");
    close.setDiscard(false);
    EnvironmentIdeChangeDirectoryDTO directory = new EnvironmentIdeChangeDirectoryDTO();
    directory.setDocumentId(DOCUMENT);
    directory.setBindingEpoch("3");
    directory.setDirectory("/tmp");

    service.changeDirectory(ENV, VIEW, directory);
    service.focusTerminal(ENV, VIEW, binding);
    service.collapseMobile(ENV, VIEW, binding);
    service.closeView(ENV, VIEW, close);
    service.listViews(ENV);

    assertEquals(
        List.of("changeDirectory", "focusTerminal", "collapseMobile", "closeView", "listViews"),
        client.calls);
  }

  private static EnvironmentIdeOpenViewDTO openViewRequest() {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDocumentId(DOCUMENT);
    request.setEventNodeId(EVENT_NODE);
    request.setConnectionId(CONNECTION);
    request.setClaimId(CLAIM);
    request.setMode("create");
    request.setDirectory("/home/dev/project");
    return request;
  }

  private static final class StubRegistry implements ClusterNodeRegistry {

    private Optional<ClusterOwnerRoute> current = Optional.empty();
    private int calls;

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

    RecordingClient() {
      super(
          HttpClient.newHttpClient(),
          JsonMapper.builder().build(),
          new HmacClusterInternalAuthentication(
              SECRET, NODE_A, Clock.systemUTC(), Duration.ofSeconds(30), 64));
      view.setViewId(VIEW);
      view.setEnvironmentId(ENV.toString());
      view.setDirectory("/home/dev/project");
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
              .setViewId(command.getViewId())
              .setBindingEpoch(1L)
              .setDirectory("/home/dev/project")
              .setWorkspacePath("/daemon/workspaces/" + command.getViewId());
      if (command.hasDocumentId()) {
        builder.setDocumentId(command.getDocumentId());
      }
      if (command.hasEventNodeId()) {
        builder.setEventNodeId(command.getEventNodeId());
      }
      if (command.hasConnectionId()) {
        builder.setConnectionId(command.getConnectionId());
      }
      if (command.hasClaimId()) {
        builder.setClaimId(command.getClaimId());
      }
      reply.complete(builder.build());
      return new IdeRequest(
          new DaemonConnectionIdentity(environmentId, "daemon-instance"), UUID.randomUUID(), reply);
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(new DaemonConnectionIdentity(environmentId, "daemon-instance"));
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
        UUID documentId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId) {
      return true;
    }

    @Override
    public Optional<Proofs> activeBoundProofs(
        UUID eventNodeId, UUID connectionId, UUID documentId) {
      return Optional.empty();
    }
  }

  private static final class NoopAuthorization implements IdeViewAuthorizationListener {

    @Override
    public void ensureViewCapacity(EnvironmentId environmentId) {}

    @Override
    public void viewOpened(
        DaemonConnectionIdentity identity,
        String viewId,
        long bindingEpoch,
        String workspacePath) {}

    @Override
    public void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {}
  }
}
