package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.DaemonIdeCodec;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeViewState;
import fun.fengwk.kkstudio.ide.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 意图：控制服务只在事件节点确认 exact 浏览器意图后下发命令；成功绑定完全采用 Daemon 回执，所有文档命令必须绑定 document+epoch， 旧 epoch 的
 * detach/close 立即冻结本地授权且不重放不确定结果。
 */
class EnvironmentIdeServiceImplTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID DOCUMENT = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");
  private static final String DAEMON_INSTANCE = "daemon-instance";

  private FakeTransport transport;
  private FakeBrowserUse browserUse;
  private FakeAuthorization authorization;
  private InMemoryIdeBootstrapTicketService tickets;
  private IdeViewBindingRegistry bindings;
  private EnvironmentIdeServiceImpl service;

  @BeforeEach
  void setUp() {
    transport = new FakeTransport();
    browserUse = new FakeBrowserUse();
    authorization = new FakeAuthorization();
    tickets = new InMemoryIdeBootstrapTicketService();
    bindings = new IdeViewBindingRegistry();
    service =
        new EnvironmentIdeServiceImpl(
            transport, tickets, browserUse, bindings, Duration.ofMillis(200), authorization);
  }

  @Test
  void openViewUsesDaemonMetadataAndIssuesViewScopedBootstrapEntry() {
    transport.replies.add(
        openReply(VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, 7L, "/daemon/dir", "/daemon/ws"));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openRequest("/client/dir"));

    assertEquals(VIEW.toString(), dto.getViewId());
    assertEquals(ENV.toString(), dto.getEnvironmentId());
    assertEquals(DOCUMENT.toString(), dto.getDocumentId());
    assertEquals(EVENT_NODE.toString(), dto.getEventNodeId());
    assertEquals(CONNECTION.toString(), dto.getConnectionId());
    assertEquals(CLAIM.toString(), dto.getClaimId());
    assertEquals(DAEMON_INSTANCE, dto.getDaemonInstanceId());
    assertEquals("7", dto.getBindingEpoch());
    assertEquals("/daemon/dir", dto.getDirectory());
    assertEquals("/daemon/ws", dto.getWorkspacePath());
    assertEquals("OPEN", dto.getState());
    assertTrue(
        dto.getEntryUrl()
            .startsWith(
                EnvironmentIdeServiceImpl.IDE_ROUTE_PREFIX
                    + ENV
                    + "/"
                    + VIEW
                    + "/"
                    + EnvironmentIdeServiceImpl.BOOTSTRAP_PATH
                    + "?token="),
        () -> "entry URL must be view scoped: " + dto.getEntryUrl());

    assertEquals(1, transport.commands.size());
    IdeCommand command = transport.commands.get(0);
    assertEquals(IdeAction.OPEN_VIEW, command.action());
    assertEquals("/client/dir", command.directory());
    assertEquals(1, browserUse.queries.size());
    assertEquals(
        new IntentQuery(EVENT_NODE, CONNECTION, DOCUMENT, VIEW, CLAIM), browserUse.queries.get(0));
    assertEquals(List.of(new OpenedEvent(VIEW, 7L, "/daemon/ws")), authorization.opened);
    assertEquals(1, tickets.pendingCount());

    IdeBootstrapTicketService.Ticket ticket =
        tickets
            .consume(dto.getEntryUrl().substring(dto.getEntryUrl().indexOf("token=") + 6))
            .orElseThrow();
    assertEquals(VIEW.toString(), ticket.viewId());
    assertEquals(7L, ticket.bindingEpoch());
    assertEquals("/daemon/ws", ticket.workspacePath());
  }

  @Test
  void openViewFailsClosedWithoutExactBrowserIntent() {
    browserUse.matched = false;

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> service.openView(ENV, openRequest(null)));

    assertTrue(transport.commands.isEmpty());
    assertTrue(authorization.opened.isEmpty());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void authorizationFailureLeavesNoRoutableBinding() {
    authorization.failOpened = true;
    transport.replies.add(
        openReply(VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, 7L, "/daemon/dir", "/daemon/ws"));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> service.openView(ENV, openRequest(null)));
    assertEquals(0, tickets.pendingCount());
    assertTrue(authorization.opened.isEmpty());

    // Daemon 端已创建但授权失败的视图，即使被权威 LIST_VIEWS 快照发现，也不能进入 owner 路由缓存。
    List<IdeViewSummary> orphan =
        List.of(
            summary(
                VIEW,
                DOCUMENT,
                EVENT_NODE,
                CONNECTION,
                CLAIM,
                7L,
                "/daemon/dir",
                "/daemon/ws",
                IdeViewState.OPEN));
    transport.replies.add(viewsReply(orphan));
    assertTrue(service.listViews(ENV).getViews().isEmpty());

    transport.replies.add(viewsReply(orphan));
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().action());
  }

  @Test
  void openViewRejectsDaemonIdentityDriftAndDoesNotAuthorize() {
    transport.replies.add(
        openReply(
            UUID.fromString("99999999-2222-3333-4444-555555555555"),
            DOCUMENT,
            EVENT_NODE,
            CONNECTION,
            CLAIM,
            7L,
            "/daemon/dir",
            "/daemon/ws"));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> service.openView(ENV, openRequest(null)));

    assertTrue(authorization.opened.isEmpty());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void allBoundCommandsCarryDocumentAndEpochAndDetachFreezesTheEpoch() {
    open();

    EnvironmentIdeViewBindingDTO binding = binding(7L);
    transport.replies.add(success(VIEW));
    service.focusTerminal(ENV, VIEW.toString(), binding);
    IdeCommand focus = transport.commands.getLast();
    assertEquals(IdeAction.FOCUS_TERMINAL, focus.action());
    assertEquals(DOCUMENT.toString(), focus.documentId());
    assertEquals(7L, focus.bindingEpoch());

    transport.replies.add(success(VIEW));
    service.detachView(ENV, VIEW.toString(), binding);
    IdeCommand detach = transport.commands.getLast();
    assertEquals(IdeAction.DETACH_VIEW, detach.action());
    assertEquals(DOCUMENT.toString(), detach.documentId());
    assertEquals(7L, detach.bindingEpoch());
    assertTrue(authorization.closed.contains(new Closed(VIEW, 7L)));

    int before = transport.commands.size();
    transport.replies.add(viewsReply(List.of()));
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.focusTerminal(ENV, VIEW.toString(), binding));
    assertEquals(before + 1, transport.commands.size());
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().action());
  }

  @Test
  void closeRequiresDiscardAndRevokesAuthorizationOnViewGone() {
    open();
    EnvironmentIdeCloseViewDTO request = new EnvironmentIdeCloseViewDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");

    assertThrows(
        AiValidationException.class, () -> service.closeView(ENV, VIEW.toString(), request));
    assertTrue(
        transport.commands.stream().noneMatch(command -> command.action() == IdeAction.CLOSE_VIEW));

    request.setDiscard(false);
    transport.replies.add(
        new IdeReply(
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "VIEW_GONE",
            "view is gone"));
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.closeView(ENV, VIEW.toString(), request));

    assertTrue(authorization.closed.contains(new Closed(VIEW, 7L)));
    assertEquals(0, tickets.pendingCount());

    EnvironmentIdeCloseViewDTO canonical = new EnvironmentIdeCloseViewDTO();
    canonical.setDocumentId(DOCUMENT.toString());
    canonical.setBindingEpoch("7");
    canonical.setDiscard(true);
    int beforeInvalidView = transport.commands.size();
    // 测试意图：请求体本身合法时，大写 viewId 仍必须在发命令前按校验错误拒绝。
    assertThrows(
        AiValidationException.class,
        () -> service.closeView(ENV, "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", canonical));
    assertEquals(beforeInvalidView, transport.commands.size());
  }

  @Test
  void changeDirectoryUsesConfirmedDirectoryAndRejectsOldEpochAfterResume() {
    open();
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/client/link/.");
    transport.wireReplies.add(
        "{\"ok\":true,\"viewId\":\"" + VIEW + "\",\"directory\":\"/daemon/next\"}");

    EnvironmentIdeViewDTO changed = service.changeDirectory(ENV, VIEW.toString(), request);
    assertEquals("/daemon/next", changed.getDirectory());
    assertEquals("/daemon/next", bindings.find(ENV, VIEW).orElseThrow().directory());
    assertEquals("/client/link/.", transport.commands.getLast().directory());

    transport.replies.add(
        viewsReply(
            List.of(
                summary(
                    VIEW,
                    DOCUMENT,
                    EVENT_NODE,
                    CONNECTION,
                    CLAIM,
                    8L,
                    "/daemon/next",
                    "/daemon/ws",
                    IdeViewState.OPEN))));
    service.listViews(ENV);
    transport.replies.add(
        viewsReply(
            List.of(
                summary(
                    VIEW,
                    DOCUMENT,
                    EVENT_NODE,
                    CONNECTION,
                    CLAIM,
                    8L,
                    "/daemon/next",
                    "/daemon/ws",
                    IdeViewState.OPEN))));
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().action());
    assertTrue(
        transport.commands.stream()
            .noneMatch(
                command ->
                    command.action() == IdeAction.FOCUS_TERMINAL && command.bindingEpoch() == 7L));
  }

  @Test
  void changeDirectoryRejectsUnconfirmedOrMismatchedReplyWithoutUpdatingBinding() {
    open();
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/client/link/.");
    // Missing and blank directory are not confirmation, and neither is another view's reply.
    for (IdeReply reply :
        List.of(
            success(VIEW),
            new IdeReply(true, VIEW, null, null, null, null, null, "   ", null, null, null, null),
            new IdeReply(
                true, null, null, null, null, null, null, "/daemon/next", null, null, null, null),
            new IdeReply(
                true,
                UUID.fromString("99999999-2222-3333-4444-555555555555"),
                null,
                null,
                null,
                null,
                null,
                "/daemon/next",
                null,
                null,
                null,
                null))) {
      transport.replies.add(reply);
      int before = transport.commands.size();
      AiEnvironmentIdeUnavailableException error =
          assertThrows(
              AiEnvironmentIdeUnavailableException.class,
              () -> service.changeDirectory(ENV, VIEW.toString(), request));
      assertFalse(error.getMessage().contains(request.getDirectory()));
      assertEquals(before + 1, transport.commands.size(), "no automatic command retry");
      assertEquals("/daemon/dir", bindings.find(ENV, VIEW).orElseThrow().directory());
    }
    // The production transport decodes the wire reply in the completion stage.
    transport.wireReplies.add("{\"ok\":true,\"viewId\":\"" + VIEW + "\",\"directory\":42}");
    int before = transport.commands.size();
    AiEnvironmentIdeUnavailableException error =
        assertThrows(
            AiEnvironmentIdeUnavailableException.class,
            () -> service.changeDirectory(ENV, VIEW.toString(), request));
    assertFalse(error.getMessage().contains(request.getDirectory()));
    assertEquals(before + 1, transport.commands.size(), "no automatic command retry");
    assertEquals("/daemon/dir", bindings.find(ENV, VIEW).orElseThrow().directory());
  }

  @Test
  void listViewsRebuildsRegistryAndRevokesStaleAuthorization() {
    open();
    transport.replies.add(
        viewsReply(
            List.of(
                summary(
                    VIEW,
                    DOCUMENT,
                    EVENT_NODE,
                    CONNECTION,
                    CLAIM,
                    8L,
                    "/daemon/dir",
                    "/daemon/ws",
                    IdeViewState.OPEN))));

    EnvironmentIdeViewsDTO views = service.listViews(ENV);

    assertEquals(1, views.getViews().size());
    assertEquals("8", views.getViews().get(0).getBindingEpoch());
    assertTrue(authorization.closed.contains(new Closed(VIEW, 7L)));
  }

  @Test
  void daemonGenerationReplacementInvalidatesCachedBindings() {
    open();
    transport.identity = new DaemonConnectionIdentity(ENV, "replacement-daemon");
    transport.replies.add(viewsReply(List.of()));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));

    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().action());
    assertTrue(
        transport.commands.stream()
            .noneMatch(command -> command.action() == IdeAction.FOCUS_TERMINAL));
  }

  @Test
  void unknownOutcomeIsNotReplayed() {
    transport.neverCompletes.set(true);

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> service.openView(ENV, openRequest(null)));

    // 原 claim 超时后只追加一次 LIST_VIEWS 对账，不重发 OPEN_VIEW；对账也超时则保持不可用。
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
    assertEquals(2, transport.abandoned);
  }

  /** 原 claim 结果未知时只读 LIST_VIEWS 对账，不重发 OPEN_VIEW；完全匹配的回执可直接完成入口签发。 */
  @Test
  void unknownClaimReconcilesByOriginalClaimWithoutReplayingOpenView() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    transport.replies.add(
        viewsReply(
            List.of(
                summary(
                    VIEW,
                    DOCUMENT,
                    EVENT_NODE,
                    CONNECTION,
                    CLAIM,
                    7L,
                    "/daemon/dir",
                    "/daemon/ws",
                    IdeViewState.OPEN))));

    EnvironmentIdeViewDTO dto = service.openView(ENV, openRequest(null));

    assertEquals("7", dto.getBindingEpoch());
    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
    assertEquals(1, transport.abandoned);
    assertEquals(2, browserUse.queries.size(), "claim 与对账后都必须核对 exact intent");
    assertEquals(1, tickets.pendingCount());
  }

  /** 对账快照里的 claim 身份漂移时保持结果不确定，不认领其它 claim 的绑定。 */
  @Test
  void unknownClaimWithDriftedSnapshotIsNotAdopted() {
    transport.failNext.set(IdeAction.OPEN_VIEW);
    transport.replies.add(
        viewsReply(
            List.of(
                summary(
                    VIEW,
                    DOCUMENT,
                    EVENT_NODE,
                    CONNECTION,
                    UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                    7L,
                    "/daemon/dir",
                    "/daemon/ws",
                    IdeViewState.OPEN))));

    assertThrows(
        AiEnvironmentIdeUnavailableException.class, () -> service.openView(ENV, openRequest(null)));

    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
    assertEquals(0, tickets.pendingCount());
  }

  /** 终端/目录命令没有 claim 对账语义：超时后只失败，不得自动重发。 */
  @Test
  void terminalAndChangeOutcomesAreNotReplayed() {
    open();
    transport.failNext.set(IdeAction.FOCUS_TERMINAL);
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals(IdeAction.FOCUS_TERMINAL, transport.commands.getLast().action());
    assertEquals(2, transport.commands.size());

    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/next");
    transport.failNext.set(IdeAction.CHANGE_DIRECTORY);
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> service.changeDirectory(ENV, VIEW.toString(), request));
    assertEquals(IdeAction.CHANGE_DIRECTORY, transport.commands.getLast().action());
    assertEquals(3, transport.commands.size());
    assertEquals(IdeAction.OPEN_VIEW, transport.commands.getFirst().action());
  }

  private List<IdeAction> actions() {
    return transport.commands.stream().map(IdeCommand::action).toList();
  }

  private void open() {
    transport.replies.add(
        openReply(VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, 7L, "/daemon/dir", "/daemon/ws"));
    service.openView(ENV, openRequest(null));
  }

  private static EnvironmentIdeOpenViewDTO openRequest(String directory) {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW.toString());
    request.setDocumentId(DOCUMENT.toString());
    request.setEventNodeId(EVENT_NODE.toString());
    request.setConnectionId(CONNECTION.toString());
    request.setClaimId(CLAIM.toString());
    request.setMode("create");
    request.setDirectory(directory);
    return request;
  }

  private static EnvironmentIdeViewBindingDTO binding(long epoch) {
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setDocumentId(DOCUMENT.toString());
    binding.setBindingEpoch(Long.toString(epoch));
    return binding;
  }

  private static IdeReply openReply(
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      long epoch,
      String directory,
      String workspacePath) {
    return new IdeReply(
        true,
        viewId,
        documentId,
        eventNodeId,
        connectionId,
        claimId,
        epoch,
        directory,
        workspacePath,
        null,
        null,
        null);
  }

  private static IdeReply success(UUID viewId) {
    return new IdeReply(true, viewId, null, null, null, null, null, null, null, null, null, null);
  }

  private static IdeReply viewsReply(List<IdeViewSummary> views) {
    return new IdeReply(true, null, null, null, null, null, null, null, null, views, null, null);
  }

  private static IdeViewSummary summary(
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      long epoch,
      String directory,
      String workspacePath,
      IdeViewState state) {
    return new IdeViewSummary(
        viewId,
        documentId,
        eventNodeId,
        connectionId,
        claimId,
        epoch,
        directory,
        workspacePath,
        state);
  }

  private record IntentQuery(
      UUID eventNodeId, UUID connectionId, UUID documentId, UUID viewId, UUID claimId) {}

  private record Closed(UUID viewId, long epoch) {}

  private record OpenedEvent(UUID viewId, long epoch, String workspacePath) {}

  private static final class FakeTransport implements IdeCommandTransport {

    private final List<IdeCommand> commands = new ArrayList<>();
    private final Deque<IdeReply> replies = new ArrayDeque<>();
    private final Deque<String> wireReplies = new ArrayDeque<>();
    private final AtomicBoolean neverCompletes = new AtomicBoolean();
    private final AtomicReference<IdeAction> failNext = new AtomicReference<>();
    private DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, DAEMON_INSTANCE);
    private int abandoned;

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeReply> future = new CompletableFuture<>();
      IdeAction failAction = failNext.get();
      if (failAction == command.action() && failNext.compareAndSet(failAction, null)) {
        // 只让指定命令一次结果不确定；后续对账命令使用脚本化回执。
      } else if (!neverCompletes.get()) {
        if (wireReplies.isEmpty()) {
          future.complete(replies.removeFirst());
        } else {
          future =
              CompletableFuture.completedFuture(wireReplies.removeFirst())
                  .thenApply(new DaemonIdeCodec()::decodeReply);
        }
      }
      return new IdeRequest(identity, UUID.randomUUID(), future);
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
      throw new AssertionError("control service must not send leases");
    }

    @Override
    public void abandonIde(UUID invocationId) {
      abandoned++;
    }
  }

  private static final class FakeBrowserUse implements IdeBrowserUseGateway {

    private final List<IntentQuery> queries = new ArrayList<>();
    private boolean matched = true;

    @Override
    public boolean findExactIntent(
        UUID eventNodeId,
        UUID connectionId,
        UUID documentId,
        EnvironmentId environmentId,
        UUID viewId,
        UUID claimId) {
      queries.add(new IntentQuery(eventNodeId, connectionId, documentId, viewId, claimId));
      return matched;
    }

    @Override
    public Optional<Proofs> activeBoundProofs(
        UUID eventNodeId, UUID connectionId, UUID documentId) {
      return Optional.empty();
    }
  }

  private static final class FakeAuthorization implements IdeViewAuthorizationListener {

    private final List<OpenedEvent> opened = new ArrayList<>();
    private final List<Closed> closed = new ArrayList<>();
    private boolean capacityExhausted;
    private boolean failOpened;

    @Override
    public void ensureViewCapacity(EnvironmentId environmentId) {
      if (capacityExhausted) {
        throw new IllegalStateException("capacity exhausted");
      }
    }

    @Override
    public void viewOpened(
        DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {
      if (failOpened) {
        throw new IllegalStateException("authorization capacity exhausted");
      }
      opened.add(new OpenedEvent(UUID.fromString(viewId), bindingEpoch, workspacePath));
    }

    @Override
    public void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {
      closed.add(new Closed(UUID.fromString(viewId), bindingEpoch));
    }
  }
}
