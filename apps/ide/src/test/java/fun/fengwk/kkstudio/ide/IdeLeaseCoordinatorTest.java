package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 意图：heartbeat 只提供 round，续租必须经 LIST_VIEWS 后按 eventNode/connection/document 查询事件节点，并且只写入
 * view+doc+epoch 全匹配的 证明；查询失败、身份换代或无轮次都不能凭心跳本身续租。
 */
class IdeLeaseCoordinatorTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW_1 = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID VIEW_2 = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID DOCUMENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-instance");

  private FakeViews views;
  private FakeTransport transport;
  private FakeGateway gateway;
  private IdeLeaseCoordinator coordinator;

  @BeforeEach
  void setUp() {
    views = new FakeViews();
    transport = new FakeTransport();
    gateway = new FakeGateway();
    coordinator = new IdeLeaseCoordinator(views, transport, gateway, Runnable::run);
  }

  @Test
  void renewsOnlyExactViewDocumentEpochProofs() {
    views.response = views(view(VIEW_1, 7L), view(VIEW_2, 8L));
    gateway.proofs.put(
        new Group(EVENT_NODE, CONNECTION, DOCUMENT),
        new IdeBrowserUseGateway.Proofs(
            DOCUMENT,
            1L,
            List.of(
                proof(VIEW_1, DOCUMENT, 7L),
                proof(VIEW_2, DOCUMENT, 7L),
                proof(VIEW_1, UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000"), 7L))));

    coordinator.onHeartbeat(ENV, IDENTITY, 42L);

    assertEquals(1, transport.leases.size());
    assertEquals(42L, transport.leases.getFirst().round());
    assertEquals(
        List.of(new DaemonIdeLease.Binding(VIEW_1, DOCUMENT, 7L)),
        transport.leases.getFirst().bindings());
    assertEquals(List.of(IDENTITY), transport.expectedIdentities);
  }

  @Test
  void failedEventNodeQueryDoesNotRenewThatGroupButDoesNotBlockOtherGroups() {
    UUID otherNode = UUID.fromString("dddddddd-eeee-ffff-0000-111111111111");
    EnvironmentIdeViewDTO first = view(VIEW_1, 7L);
    first.setEventNodeId(EVENT_NODE.toString());
    EnvironmentIdeViewDTO second = view(VIEW_2, 8L);
    second.setEventNodeId(otherNode.toString());
    views.response = views(first, second);
    gateway.failures.add(new Group(EVENT_NODE, CONNECTION, DOCUMENT));
    gateway.proofs.put(
        new Group(otherNode, CONNECTION, DOCUMENT),
        new IdeBrowserUseGateway.Proofs(DOCUMENT, 1L, List.of(proof(VIEW_2, DOCUMENT, 8L))));

    coordinator.onHeartbeat(ENV, IDENTITY, 43L);

    assertEquals(
        List.of(new DaemonIdeLease.Binding(VIEW_2, DOCUMENT, 8L)),
        transport.leases.getFirst().bindings());
  }

  @Test
  void replacedDaemonGenerationAbortsBeforeListViewOrLease() {
    transport.identity = new DaemonConnectionIdentity(ENV, "other-daemon");

    coordinator.onHeartbeat(ENV, IDENTITY, 44L);

    assertEquals(0, views.calls);
    assertTrue(transport.leases.isEmpty());
  }

  /** 对账查询进行中发生换代的场景：查询返回后必须再次核对身份，旧轮次不得落入新连接。 */
  @Test
  void generationReplacedDuringProofQueryDoesNotSendLateLease() {
    views.response = views(view(VIEW_1, 7L));
    gateway.proofs.put(
        new Group(EVENT_NODE, CONNECTION, DOCUMENT),
        new IdeBrowserUseGateway.Proofs(DOCUMENT, 1L, List.of(proof(VIEW_1, DOCUMENT, 7L))));
    gateway.afterQuery =
        () -> transport.identity = new DaemonConnectionIdentity(ENV, "other-daemon");

    coordinator.onHeartbeat(ENV, IDENTITY, 44L);

    assertEquals(1, views.calls);
    assertTrue(transport.leases.isEmpty());
    assertTrue(transport.expectedIdentities.isEmpty());
  }

  @Test
  void noHeartbeatRoundDoesNothing() {
    coordinator.onHeartbeat(ENV, IDENTITY, null);

    assertEquals(0, views.calls);
    assertTrue(transport.leases.isEmpty());
  }

  @Test
  void concurrentHeartbeatIsCoalesced() {
    Deque<Runnable> pending = new ArrayDeque<>();
    coordinator = new IdeLeaseCoordinator(views, transport, gateway, pending::add);
    views.response = views(view(VIEW_1, 7L));

    coordinator.onHeartbeat(ENV, IDENTITY, 45L);
    coordinator.onHeartbeat(ENV, IDENTITY, 46L);
    assertEquals(1, pending.size());

    pending.removeFirst().run();
    coordinator.onHeartbeat(ENV, IDENTITY, 47L);

    assertEquals(1, pending.size());
    assertEquals(1, views.calls);
    pending.removeFirst().run();
    assertEquals(2, views.calls);
  }

  private static EnvironmentIdeViewsDTO views(EnvironmentIdeViewDTO... entries) {
    EnvironmentIdeViewsDTO dto = new EnvironmentIdeViewsDTO();
    dto.setViews(List.of(entries));
    return dto;
  }

  private static EnvironmentIdeViewDTO view(UUID viewId, long epoch) {
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(viewId.toString());
    dto.setEnvironmentId(ENV.toString());
    dto.setDocumentId(DOCUMENT.toString());
    dto.setEventNodeId(EVENT_NODE.toString());
    dto.setConnectionId(CONNECTION.toString());
    dto.setClaimId(CLAIM.toString());
    dto.setDaemonInstanceId(IDENTITY.daemonInstanceId());
    dto.setBindingEpoch(Long.toString(epoch));
    dto.setDirectory("/dir");
    dto.setWorkspacePath("/ws");
    dto.setState("OPEN");
    return dto;
  }

  private static IdeBrowserUseGateway.Proof proof(UUID viewId, UUID documentId, long epoch) {
    return new IdeBrowserUseGateway.Proof(ENV, viewId, documentId, epoch);
  }

  private record Group(UUID eventNodeId, UUID connectionId, UUID documentId) {}

  private static final class FakeViews implements EnvironmentIdeService {

    private EnvironmentIdeViewsDTO response;
    private int calls;

    @Override
    public EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId) {
      calls++;
      return response;
    }

    @Override
    public EnvironmentIdeViewDTO openView(
        EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request) {
      throw new AssertionError("unexpected open");
    }

    @Override
    public EnvironmentIdeViewDTO changeDirectory(
        EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request) {
      throw new AssertionError("unexpected change");
    }

    @Override
    public void focusTerminal(
        EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request) {
      throw new AssertionError("unexpected focus");
    }

    @Override
    public void closeView(
        EnvironmentId environmentId, String viewId, EnvironmentIdeCloseViewDTO request) {
      throw new AssertionError("unexpected close");
    }

    @Override
    public void detachView(
        EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request) {
      throw new AssertionError("unexpected detach");
    }
  }

  private static final class FakeTransport implements IdeCommandTransport {

    private final List<DaemonIdeLease> leases = new ArrayList<>();
    private final List<DaemonConnectionIdentity> expectedIdentities = new ArrayList<>();
    private DaemonConnectionIdentity identity = IDENTITY;

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      throw new AssertionError("unexpected command");
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(identity);
    }

    @Override
    public void sendIdeLease(
        EnvironmentId environmentId,
        DaemonConnectionIdentity expectedIdentity,
        DaemonIdeLease lease) {
      expectedIdentities.add(expectedIdentity);
      leases.add(lease);
    }

    @Override
    public void abandonIde(UUID invocationId) {}
  }

  private static final class FakeGateway implements IdeBrowserUseGateway {

    private final Map<Group, Proofs> proofs = new HashMap<>();
    private final Set<Group> failures = new HashSet<>();
    private Runnable afterQuery = () -> {};

    @Override
    public boolean findExactIntent(
        UUID eventNodeId,
        UUID connectionId,
        UUID documentId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId) {
      throw new AssertionError("unexpected intent query");
    }

    @Override
    public Optional<Proofs> activeBoundProofs(
        UUID eventNodeId, UUID connectionId, UUID documentId) {
      Group group = new Group(eventNodeId, connectionId, documentId);
      if (failures.contains(group)) {
        throw new IllegalStateException("event node unavailable");
      }
      Optional<Proofs> result = Optional.ofNullable(proofs.get(group));
      afterQuery.run();
      return result;
    }
  }
}
