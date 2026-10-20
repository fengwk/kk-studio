package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;
import fun.fengwk.kkstudio.platform.environment.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

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
 * 意图：组合服务必须按 owner 快照决定本地/远端交付——本地直接委托本地 delegate 且不触碰内部客户端，远端恰好一次内部调用；无路由时在任何 交付前 fail-closed，非法
 * viewId 在路由查询前拒绝。
 */
class ClusterEnvironmentIdeServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  private final StubRegistry registry = new StubRegistry();
  private final RecordingClient client = new RecordingClient();
  private final RecordingTransport localTransport = new RecordingTransport();
  private ClusterEnvironmentIdeService service;

  @BeforeEach
  void setUp() {
    IdeOriginProperties origin = new IdeOriginProperties();
    origin.setOriginTemplate("https://{environmentId}.ide.example.com");
    EnvironmentIdeServiceImpl local =
        new EnvironmentIdeServiceImpl(
            localTransport, origin, new InMemoryIdeBootstrapTicketService());
    service = new ClusterEnvironmentIdeService(registry, client, local);
  }

  @Test
  void localOwnerDelegatesToTheLocalDelegate() {
    registry.current = Optional.of(ClusterIdeOwnerRoute.local(NODE_A));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openViewRequest());

    assertEquals(VIEW, dto.getViewId());
    assertEquals(IdeAction.OPEN_VIEW, localTransport.commands.get(0).action());
    assertTrue(client.calls.isEmpty());
  }

  @Test
  void remoteOwnerUsesTheInternalClientExactlyOnce() {
    registry.current =
        Optional.of(ClusterIdeOwnerRoute.remote(NODE_B, URI.create("http://b.internal")));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openViewRequest());

    assertSame(client.view, dto);
    assertEquals(List.of("openView"), client.calls);
    assertTrue(localTransport.commands.isEmpty());
  }

  @Test
  void missingRouteFailsClosedBeforeAnyDelivery() {
    registry.current = Optional.empty();

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> service.openView(ENV, openViewRequest()));
    assertTrue(client.calls.isEmpty());
    assertTrue(localTransport.commands.isEmpty());
  }

  @Test
  void invalidViewIdIsRejectedBeforeRouting() {
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDirectory("/tmp");

    assertThrows(
        AiValidationException.class, () -> service.changeDirectory(ENV, "not-a-uuid", request));
    assertEquals(0, registry.calls);
    assertTrue(client.calls.isEmpty());
  }

  @Test
  void terminalAndCloseRouteToClientWhenRemote() {
    registry.current =
        Optional.of(ClusterIdeOwnerRoute.remote(NODE_B, URI.create("http://b.internal")));

    service.focusTerminal(ENV, VIEW);
    service.closeView(ENV, VIEW);

    assertEquals(List.of("focusTerminal", "closeView"), client.calls);
  }

  private static EnvironmentIdeOpenViewDTO openViewRequest() {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDirectory("/home/dev/project");
    return request;
  }

  private static final class StubRegistry implements ClusterNodeRegistry {

    private Optional<ClusterIdeOwnerRoute> current = Optional.empty();
    private int calls;

    @Override
    public Optional<ClusterIdeOwnerRoute> route(EnvironmentId environmentId) {
      calls++;
      return current;
    }

    @Override
    public Optional<URI> liveInternalOrigin(UUID nodeInstanceId) {
      return Optional.empty();
    }
  }

  private static final class RecordingClient extends ClusterInternalClient {

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
        EnvironmentId environmentId,
        ClusterIdeOwnerRoute owner,
        EnvironmentIdeOpenViewDTO request) {
      calls.add("openView");
      return view;
    }

    @Override
    public EnvironmentIdeViewDTO changeDirectory(
        EnvironmentId environmentId,
        ClusterIdeOwnerRoute owner,
        String viewId,
        EnvironmentIdeChangeDirectoryDTO request) {
      calls.add("changeDirectory");
      return view;
    }

    @Override
    public void focusTerminal(
        EnvironmentId environmentId, ClusterIdeOwnerRoute owner, String viewId) {
      calls.add("focusTerminal");
    }

    @Override
    public void closeView(EnvironmentId environmentId, ClusterIdeOwnerRoute owner, String viewId) {
      calls.add("closeView");
    }
  }

  private static final class RecordingTransport implements IdeCommandTransport {

    private final List<IdeCommand> commands = new ArrayList<>();

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeReply> reply = new CompletableFuture<>();
      reply.complete(IdeReply.ok(VIEW, "/home/dev/project", "/daemon/workspaces/" + VIEW));
      return new IdeRequest(
          new IdeSessionIdentity(environmentId, "daemon-instance", UUID.randomUUID()),
          UUID.randomUUID(),
          reply);
    }

    @Override
    public Optional<IdeSessionIdentity> ideSessionIdentity(EnvironmentId environmentId) {
      return Optional.of(
          new IdeSessionIdentity(environmentId, "daemon-instance", UUID.randomUUID()));
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }
}
