package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewState;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayConstants;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 意图：控制服务只在事件节点确认 exact 浏览器意图后下发命令；成功绑定完全采用 Daemon 回执，所有文档命令必须绑定 document+epoch， 旧 epoch 的 close
 * 立即撤销本地授权且不重放不确定结果。
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
  private IdeBootstrapTicketService tickets;
  private IdeViewBindingRegistry bindings;
  private EnvironmentIdeServiceImpl service;

  @BeforeEach
  void setUp() {
    transport = new FakeTransport();
    browserUse = new FakeBrowserUse();
    authorization = new FakeAuthorization();
    tickets = new IdeBootstrapTicketService();
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
    assertEquals(EnvironmentIdeViewState.OPEN, dto.getState());
    assertTrue(
        dto.getEntryUrl()
            .startsWith(
                IdeGatewayConstants.ROUTE_PREFIX
                    + ENV
                    + "/"
                    + VIEW
                    + IdeGatewayConstants.BOOTSTRAP_ENDPOINT
                    + "?token="),
        () -> "entry URL must be view scoped: " + dto.getEntryUrl());

    assertEquals(1, transport.commands.size());
    IdeCommand command = transport.commands.get(0);
    assertEquals(IdeAction.OPEN_VIEW, command.getAction());
    assertEquals("/client/dir", command.getDirectory());
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

    assertThrows(DomainException.class, () -> service.openView(ENV, openRequest(null)));

    assertTrue(transport.commands.isEmpty());
    assertTrue(authorization.opened.isEmpty());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void authorizationFailureLeavesNoRoutableBinding() {
    authorization.failOpened = true;
    transport.replies.add(
        openReply(VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, 7L, "/daemon/dir", "/daemon/ws"));

    assertThrows(DomainException.class, () -> service.openView(ENV, openRequest(null)));
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
        DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().getAction());
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

    assertThrows(DomainException.class, () -> service.openView(ENV, openRequest(null)));

    assertTrue(authorization.opened.isEmpty());
    assertEquals(0, tickets.pendingCount());
  }

  @Test
  void boundCommandsCarryDocumentAndEpochAndCloseRevokesTheBinding() {
    open();

    EnvironmentIdeViewBindingDTO binding = binding(7L);
    transport.replies.add(success(VIEW));
    service.focusTerminal(ENV, VIEW.toString(), binding);
    IdeCommand focus = transport.commands.getLast();
    assertEquals(IdeAction.FOCUS_TERMINAL, focus.getAction());
    assertEquals(DOCUMENT.toString(), focus.getDocumentId());
    assertEquals(7L, focus.getBindingEpoch());

    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    close.setDocumentId(DOCUMENT.toString());
    close.setBindingEpoch("7");
    close.setDiscard(true);
    transport.replies.add(success(VIEW));
    service.closeView(ENV, VIEW.toString(), close);
    IdeCommand command = transport.commands.getLast();
    assertEquals(IdeAction.CLOSE_VIEW, command.getAction());
    assertEquals(DOCUMENT.toString(), command.getDocumentId());
    assertEquals(7L, command.getBindingEpoch());
    assertTrue(authorization.closed.contains(new Closed(VIEW, 7L)));

    int before = transport.commands.size();
    transport.replies.add(viewsReply(List.of()));
    assertThrows(DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding));
    assertEquals(before + 1, transport.commands.size());
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().getAction());
  }

  /** 用户取消关闭不撤销当前绑定，并提供可跨节点识别的原因。 */
  @Test
  void cancelledCloseKeepsBindingAndReturnsCancellationReason() {
    open();
    EnvironmentIdeCloseViewDTO request = new EnvironmentIdeCloseViewDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDiscard(false);
    transport.replies.add(
        IdeResult.newBuilder().setOk(false).setCode("CANCELLED").setMessage("cancelled").build());
    DomainException error =
        assertThrows(DomainException.class, () -> service.closeView(ENV, VIEW.toString(), request));
    assertEquals("IDE_CLOSE_CANCELLED", error.reason());
    assertTrue(bindings.find(ENV, VIEW).isPresent());
    assertTrue(authorization.closed.isEmpty());
  }

  @Test
  void closeRevokesAuthorizationOnViewGone() {
    open();
    EnvironmentIdeCloseViewDTO request = new EnvironmentIdeCloseViewDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDiscard(false);
    transport.replies.add(
        IdeResult.newBuilder()
            .setOk(false)
            .setCode("VIEW_GONE")
            .setMessage("view is gone")
            .build());
    assertThrows(DomainException.class, () -> service.closeView(ENV, VIEW.toString(), request));

    assertTrue(authorization.closed.contains(new Closed(VIEW, 7L)));
    assertEquals(0, tickets.pendingCount());

    EnvironmentIdeCloseViewDTO canonical = new EnvironmentIdeCloseViewDTO();
    canonical.setDocumentId(DOCUMENT.toString());
    canonical.setBindingEpoch("7");
    canonical.setDiscard(true);
    int beforeInvalidView = transport.commands.size();
    // 测试意图：请求体本身合法时，大写 viewId 仍必须在发命令前按校验错误拒绝。
    assertThrows(
        DomainException.class,
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
    transport.replies.add(
        IdeResult.newBuilder()
            .setOk(true)
            .setViewId(VIEW.toString())
            .setDirectory("/daemon/next")
            .build());
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
                    "/daemon/next",
                    "/daemon/ws",
                    IdeViewState.CLOSING))));

    EnvironmentIdeViewDTO changed = service.changeDirectory(ENV, VIEW.toString(), request);
    assertEquals("/daemon/next", changed.getDirectory());
    assertEquals(EnvironmentIdeViewState.CLOSING, changed.getState());
    assertTrue(bindings.find(ENV, VIEW).orElseThrow().matches(DOCUMENT, 7L));
    assertEquals("/client/link/.", transport.commands.get(1).getDirectory());
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().getAction());

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
        DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().getAction());
    assertTrue(
        transport.commands.stream()
            .noneMatch(
                command ->
                    command.getAction() == IdeAction.FOCUS_TERMINAL
                        && command.getBindingEpoch() == 7L));
  }

  /**
   * 意图：切目录已被 Daemon 确认后，刷新缓存的回读失败不能把成功结果改成失败。
   *
   * <p>响应只使用命令确认的目录与已知绑定身份，不伪造 workspace/state；缓存中的绑定保持原值，已执行的目录命令不重放。
   */
  @Test
  void changeDirectorySucceedsWhenReadbackFailsAfterConfirmation() {
    open();
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/daemon/next");
    transport.replies.add(
        IdeResult.newBuilder()
            .setOk(true)
            .setViewId(VIEW.toString())
            .setDirectory("/daemon/next")
            .build());
    transport.replies.add(
        IdeResult.newBuilder()
            .setOk(false)
            .setCode("UNAVAILABLE")
            .setMessage("daemon restarted")
            .build());

    EnvironmentIdeViewDTO changed = service.changeDirectory(ENV, VIEW.toString(), request);

    assertEquals("/daemon/next", changed.getDirectory());
    assertEquals(VIEW.toString(), changed.getViewId());
    assertEquals(DOCUMENT.toString(), changed.getDocumentId());
    assertEquals("7", changed.getBindingEpoch());
    assertNull(changed.getWorkspacePath(), "回读失败时不得伪造 Daemon workspace");
    assertNull(changed.getState(), "回读失败时不得伪造 Daemon 视图状态");
    assertTrue(bindings.find(ENV, VIEW).orElseThrow().matches(DOCUMENT, 7L));
    assertEquals(
        List.of(IdeAction.OPEN_VIEW, IdeAction.CHANGE_DIRECTORY, IdeAction.LIST_VIEWS), actions());
  }

  @Test
  void changeDirectoryRejectsUnconfirmedOrMismatchedReplyWithoutUpdatingBinding() {
    open();
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/client/link/.");
    // Missing and blank directory are not confirmation, and neither is another view's reply.
    for (IdeResult reply :
        List.of(
            success(VIEW),
            IdeResult.newBuilder()
                .setOk(true)
                .setViewId(VIEW.toString())
                .setDirectory("   ")
                .build(),
            IdeResult.newBuilder().setOk(true).setDirectory("/daemon/next").build(),
            IdeResult.newBuilder()
                .setOk(true)
                .setViewId("99999999-2222-3333-4444-555555555555")
                .setDirectory("/daemon/next")
                .build())) {
      transport.replies.add(reply);
      int before = transport.commands.size();
      DomainException error =
          assertThrows(
              DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), request));
      assertFalse(error.getMessage().contains(request.getDirectory()));
      assertEquals(before + 1, transport.commands.size(), "no automatic command retry");
      assertTrue(bindings.find(ENV, VIEW).orElseThrow().matches(DOCUMENT, 7L));
    }
    // No directory in reply fails validation.
    transport.replies.add(IdeResult.newBuilder().setOk(true).setViewId(VIEW.toString()).build());
    int before = transport.commands.size();
    DomainException error =
        assertThrows(
            DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), request));
    assertFalse(error.getMessage().contains(request.getDirectory()));
    assertEquals(before + 1, transport.commands.size(), "no automatic command retry");
    assertTrue(bindings.find(ENV, VIEW).orElseThrow().matches(DOCUMENT, 7L));
  }

  /** 目录回执后若视图已换代，不得以旧 epoch 构造成功响应。 */
  @Test
  void changeDirectoryRejectsReplyWhenAuthoritativeBindingChanged() {
    open();
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/next");
    transport.replies.add(
        IdeResult.newBuilder()
            .setOk(true)
            .setViewId(VIEW.toString())
            .setDirectory("/next")
            .build());
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
                    "/next",
                    "/daemon/ws",
                    IdeViewState.OPEN))));

    assertThrows(
        DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), request));
    assertTrue(bindings.find(ENV, VIEW).orElseThrow().matches(DOCUMENT, 8L));
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
        DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));

    assertEquals(IdeAction.LIST_VIEWS, transport.commands.getLast().getAction());
    assertTrue(
        transport.commands.stream()
            .noneMatch(command -> command.getAction() == IdeAction.FOCUS_TERMINAL));
  }

  @Test
  void unknownOutcomeIsNotReplayed() {
    transport.neverCompletes.set(true);

    assertThrows(DomainException.class, () -> service.openView(ENV, openRequest(null)));

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

    assertThrows(DomainException.class, () -> service.openView(ENV, openRequest(null)));

    assertEquals(List.of(IdeAction.OPEN_VIEW, IdeAction.LIST_VIEWS), actions());
    assertEquals(0, tickets.pendingCount());
  }

  /** 终端/目录命令没有 claim 对账语义：超时后只失败，不得自动重发。 */
  @Test
  void terminalAndChangeOutcomesAreNotReplayed() {
    open();
    transport.failNext.set(IdeAction.FOCUS_TERMINAL);
    assertThrows(
        DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    assertEquals(IdeAction.FOCUS_TERMINAL, transport.commands.getLast().getAction());
    assertEquals(2, transport.commands.size());

    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDocumentId(DOCUMENT.toString());
    request.setBindingEpoch("7");
    request.setDirectory("/next");
    transport.failNext.set(IdeAction.CHANGE_DIRECTORY);
    assertThrows(
        DomainException.class, () -> service.changeDirectory(ENV, VIEW.toString(), request));
    assertEquals(IdeAction.CHANGE_DIRECTORY, transport.commands.getLast().getAction());
    assertEquals(3, transport.commands.size());
    assertEquals(IdeAction.OPEN_VIEW, transport.commands.getFirst().getAction());
  }

  /** 普通关闭可等待原生提示，但终端和强制关闭仍会超时并放弃在途命令。 */
  @Test
  void normalCloseWaitsForReplyWhileOtherCommandsRemainBounded() throws Exception {
    open();
    transport.neverCompletes.set(true);
    assertThrows(
        DomainException.class, () -> service.focusTerminal(ENV, VIEW.toString(), binding(7L)));
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    close.setDocumentId(DOCUMENT.toString());
    close.setBindingEpoch("7");
    close.setDiscard(true);
    assertThrows(DomainException.class, () -> service.closeView(ENV, VIEW.toString(), close));
    assertEquals(2, transport.abandoned);
    close.setDiscard(false);
    transport.pendingStarted = new CountDownLatch(1);
    CompletableFuture<Void> waiting =
        CompletableFuture.runAsync(() -> service.closeView(ENV, VIEW.toString(), close));
    try {
      assertTrue(transport.pendingStarted.await(2, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> waiting.get(500, TimeUnit.MILLISECONDS));
      assertEquals(2, transport.abandoned);
      transport.pendingReply.complete(success(VIEW));
      waiting.get(2, TimeUnit.SECONDS);
      assertEquals(2, transport.abandoned);
    } finally {
      transport.pendingReply.completeExceptionally(new IllegalStateException("test cleanup"));
    }
  }

  /** 无限等待仍可中断，并且必须撤销对应在途登记。 */
  @Test
  void interruptingNormalCloseAbandonsPendingCommand() throws Exception {
    open();
    transport.neverCompletes.set(true);
    transport.pendingStarted = new CountDownLatch(1);
    EnvironmentIdeCloseViewDTO close = new EnvironmentIdeCloseViewDTO();
    close.setDocumentId(DOCUMENT.toString());
    close.setBindingEpoch("7");
    close.setDiscard(false);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              try {
                service.closeView(ENV, VIEW.toString(), close);
              } catch (RuntimeException error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    worker.start();
    try {
      assertTrue(transport.pendingStarted.await(2, TimeUnit.SECONDS));
      worker.interrupt();
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertTrue(
          failure.get() instanceof DomainException error
              && error.code() == DomainErrorCode.UNAVAILABLE
              && "IDE_UNAVAILABLE".equals(error.reason()));
      assertTrue(interrupted.get());
      assertEquals(1, transport.abandoned);
    } finally {
      worker.interrupt();
      if (transport.pendingReply != null) {
        transport.pendingReply.completeExceptionally(new IllegalStateException("test cleanup"));
      }
    }
  }

  private List<IdeAction> actions() {
    return transport.commands.stream().map(IdeCommand::getAction).toList();
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
    request.setMode(EnvironmentIdeOpenMode.CREATE);
    request.setDirectory(directory);
    return request;
  }

  private static EnvironmentIdeViewBindingDTO binding(long epoch) {
    EnvironmentIdeViewBindingDTO binding = new EnvironmentIdeViewBindingDTO();
    binding.setDocumentId(DOCUMENT.toString());
    binding.setBindingEpoch(Long.toString(epoch));
    return binding;
  }

  private static IdeResult openReply(
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      long epoch,
      String directory,
      String workspacePath) {
    IdeResult.Builder builder = IdeResult.newBuilder().setOk(true).setBindingEpoch(epoch);
    if (viewId != null) {
      builder.setViewId(viewId.toString());
    }
    if (documentId != null) {
      builder.setDocumentId(documentId.toString());
    }
    if (eventNodeId != null) {
      builder.setEventNodeId(eventNodeId.toString());
    }
    if (connectionId != null) {
      builder.setConnectionId(connectionId.toString());
    }
    if (claimId != null) {
      builder.setClaimId(claimId.toString());
    }
    if (directory != null) {
      builder.setDirectory(directory);
    }
    if (workspacePath != null) {
      builder.setWorkspacePath(workspacePath);
    }
    return builder.build();
  }

  private static IdeResult success(UUID viewId) {
    IdeResult.Builder builder = IdeResult.newBuilder().setOk(true);
    if (viewId != null) {
      builder.setViewId(viewId.toString());
    }
    return builder.build();
  }

  private static IdeResult viewsReply(List<IdeViewSummary> views) {
    IdeResult.Builder builder = IdeResult.newBuilder().setOk(true);
    if (views != null) {
      builder.addAllViews(views);
    }
    return builder.build();
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
    IdeViewSummary.Builder builder =
        IdeViewSummary.newBuilder().setBindingEpoch(epoch).setState(state);
    if (viewId != null) {
      builder.setViewId(viewId.toString());
    }
    if (documentId != null) {
      builder.setDocumentId(documentId.toString());
    }
    if (eventNodeId != null) {
      builder.setEventNodeId(eventNodeId.toString());
    }
    if (connectionId != null) {
      builder.setConnectionId(connectionId.toString());
    }
    if (claimId != null) {
      builder.setClaimId(claimId.toString());
    }
    if (directory != null) {
      builder.setDirectory(directory);
    }
    if (workspacePath != null) {
      builder.setWorkspacePath(workspacePath);
    }
    return builder.build();
  }

  private record IntentQuery(
      UUID eventNodeId, UUID connectionId, UUID documentId, UUID viewId, UUID claimId) {}

  private record Closed(UUID viewId, long epoch) {}

  private record OpenedEvent(UUID viewId, long epoch, String workspacePath) {}

  private static final class FakeTransport extends IdeCommandTransport {

    private FakeTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    private final List<IdeCommand> commands = new ArrayList<>();
    private final Deque<IdeResult> replies = new ArrayDeque<>();
    private final AtomicBoolean neverCompletes = new AtomicBoolean();
    private final AtomicReference<IdeAction> failNext = new AtomicReference<>();
    private volatile CountDownLatch pendingStarted;
    private volatile CompletableFuture<IdeResult> pendingReply;
    private DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, DAEMON_INSTANCE);
    private int abandoned;

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      commands.add(command);
      CompletableFuture<IdeResult> future = new CompletableFuture<>();
      if (pendingStarted != null) {
        pendingReply = future;
        pendingStarted.countDown();
      }
      IdeAction failAction = failNext.get();
      if (failAction == command.getAction() && failNext.compareAndSet(failAction, null)) {
        // 只让指定命令一次结果不确定；后续对账命令使用脚本化回执。
      } else if (!neverCompletes.get()) {
        future.complete(replies.removeFirst());
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

  private static final class FakeBrowserUse extends IdeBrowserUseGateway {

    private FakeBrowserUse() {
      super(mock(IdeBrowserUseClient.class));
    }

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
