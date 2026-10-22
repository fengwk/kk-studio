package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeAction;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 意图：IDE 控制服务必须把 view 请求精确翻译为对应 action 的 IDE 命令，不伪造结果：Daemon 拒绝原样上报，通道不可用/超时/中断
 * 明确失败并放弃在途命令；OPEN_VIEW 还须在缺 Origin 配置、视图容量耗尽或授权失败时于任何 Daemon 副作用之前 fail-closed，成功后铸造一次性 bootstrap
 * 入口并按真实视图 生命周期通知授权。
 */
class EnvironmentIdeServiceImplTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String INSTANCE = "33333333-3333-3333-3333-333333333333";
  private static final UUID LEASE = new UUID(0L, 77L);

  private EnvironmentIdeServiceImpl service(
      FakeTransport transport, IdeBootstrapTicketService bootstrap, Duration timeout) {
    return new EnvironmentIdeServiceImpl(transport, bootstrap, timeout);
  }

  private EnvironmentIdeServiceImpl service(
      FakeTransport transport,
      IdeBootstrapTicketService bootstrap,
      Duration timeout,
      IdeViewAuthorizationListener viewAuthorization) {
    return new EnvironmentIdeServiceImpl(transport, bootstrap, timeout, viewAuthorization);
  }

  @Test
  void openViewSendsCommandAndReturnsBootstrapEntry() {
    FakeTransport transport = new FakeTransport();
    transport.reply =
        new IdeReply(true, VIEW, "/home/dev/project", "/daemon/workspaces/" + VIEW, null);
    InMemoryIdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();

    EnvironmentIdeViewDTO dto =
        service(transport, bootstrap, Duration.ofSeconds(5))
            .openView(ENV, openViewRequest("/home/dev/project"));

    assertEquals(IdeAction.OPEN_VIEW, transport.command.action());
    assertEquals(VIEW, transport.command.viewId());
    assertEquals("/home/dev/project", transport.command.directory());
    assertEquals(ENV, transport.environment);
    assertEquals(VIEW, dto.getViewId());
    assertEquals(ENV.toString(), dto.getEnvironmentId());
    assertEquals("/home/dev/project", dto.getDirectory());
    assertTrue(dto.getEntryUrl().startsWith("/environment-ide/" + ENV + "/__ide/bootstrap?token="));
    // entryUrl 内的票据一次性绑定 workspace 路径。
    String token = tokenFrom(dto.getEntryUrl());
    IdeBootstrapTicketService.Ticket ticket = bootstrap.consume(token).orElseThrow();
    assertEquals(ENV, ticket.environmentId());
    assertEquals(VIEW, ticket.viewId());
    assertEquals("/daemon/workspaces/" + VIEW, ticket.workspacePath());
    // 入口绑定命令进入传输时的连接代际（Daemon 实例 + 租约）。
    assertEquals(INSTANCE, ticket.identity().daemonInstanceId());
    assertEquals(LEASE, ticket.identity().leaseToken());
    assertTrue(bootstrap.consume(token).isEmpty());
  }

  @Test
  void openViewFailsClosedWhenDaemonOmitsWorkspacePath() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", null, null);

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () ->
            service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                .openView(ENV, openViewRequest("/home/dev")));
  }

  @Test
  void changeDirectorySendsCommandWithoutEntryUrl() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/tmp", "/daemon/workspaces/" + VIEW, null);

    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDirectory("/tmp");
    EnvironmentIdeViewDTO dto =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
            .changeDirectory(ENV, VIEW, request);

    assertEquals(IdeAction.CHANGE_DIRECTORY, transport.command.action());
    assertEquals("/tmp", transport.command.directory());
    assertEquals("/tmp", dto.getDirectory());
    assertNull(dto.getEntryUrl());
  }

  @Test
  void focusTerminalAndCloseViewSendMatchingActions() {
    FakeTransport transport = new FakeTransport();
    transport.reply = IdeReply.ok(VIEW, null, null);
    EnvironmentIdeServiceImpl service =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5));

    service.focusTerminal(ENV, VIEW);
    assertEquals(IdeAction.FOCUS_TERMINAL, transport.command.action());
    service.closeView(ENV, VIEW);
    assertEquals(IdeAction.CLOSE_VIEW, transport.command.action());
  }

  @Test
  void daemonRejectionIsSurfacedAsValidationWithDaemonMessage() {
    FakeTransport transport = new FakeTransport();
    transport.reply = IdeReply.failure("directory is not accessible");

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                    .focusTerminal(ENV, VIEW));
    assertTrue(error.getMessage().contains("directory is not accessible"));
  }

  @Test
  void unavailableTransportMapsToIdeUnavailable() {
    FakeTransport transport = new FakeTransport();
    transport.requestFailure = new EnvironmentCapabilityUnavailableException("not ready");

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () ->
            service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                .focusTerminal(ENV, VIEW));
  }

  @Test
  void timeoutAbandonsPendingCallAndFailsClosed() {
    FakeTransport transport = new FakeTransport();
    transport.leavePending = true;

    AiEnvironmentIdeUnavailableException error =
        assertThrows(
            AiEnvironmentIdeUnavailableException.class,
            () ->
                service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofMillis(50))
                    .closeView(ENV, VIEW));
    assertTrue(error.getMessage().contains("timed out"));
    assertEquals(List.of(transport.invocationId), transport.abandoned);
  }

  @Test
  void disconnectFailureMapsToIdeUnavailable() {
    FakeTransport transport = new FakeTransport();
    transport.completeExceptionally = true;
    transport.replyFailure = new RuntimeException("connection closed");

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () ->
            service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                .closeView(ENV, VIEW));
  }

  @Test
  void invalidViewIdIsRejectedBeforeSending() {
    FakeTransport transport = new FakeTransport();
    transport.reply = IdeReply.ok(VIEW, null, null);
    EnvironmentIdeServiceImpl service =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5));

    assertThrows(AiValidationException.class, () -> service.closeView(ENV, "not-a-uuid"));
    assertNull(transport.command);
  }

  @Test
  void constructorRejectsNonPositiveCommandTimeout() {
    FakeTransport transport = new FakeTransport();
    InMemoryIdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();

    // 非正超时会让等待立刻超时并放大重试压力，构造时即拒绝。
    assertThrows(
        IllegalArgumentException.class, () -> service(transport, bootstrap, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> service(transport, bootstrap, Duration.ofSeconds(-1)));
  }

  @Test
  void defaultConstructorAppliesDocumentedCommandTimeout() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);

    EnvironmentIdeServiceImpl service =
        new EnvironmentIdeServiceImpl(transport, new InMemoryIdeBootstrapTicketService());

    assertEquals(Duration.ofSeconds(30), EnvironmentIdeServiceImpl.DEFAULT_COMMAND_TIMEOUT);
    assertEquals(VIEW, service.openView(ENV, openViewRequest("/home/dev")).getViewId());
  }

  @Test
  void changeDirectoryRejectsMissingDirectoryBeforeSending() {
    FakeTransport transport = new FakeTransport();
    transport.reply = IdeReply.ok(VIEW, "/tmp", null);
    EnvironmentIdeServiceImpl service =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5));

    // CHANGE_DIRECTORY 必须携带目录：缺失时类型化校验错误，且不得把命令送进传输。
    assertThrows(
        AiValidationException.class,
        () -> service.changeDirectory(ENV, VIEW, changeDirectoryRequest(null)));
    assertNull(transport.command);
  }

  /**
   * 意图：目录是外部输入，wire 校验（空白/超长/控制字符）必须在任何传输之前翻译为类型化校验错误，不允许把 {@link IllegalArgumentException}
   * 泄漏为未分类失败（HTTP 500），也不回显输入值。
   */
  @ParameterizedTest
  @MethodSource("invalidDirectories")
  void openViewRejectsInvalidDirectoryBeforeSending(String directory) {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);
    EnvironmentIdeServiceImpl service =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5));

    AiValidationException error =
        assertThrows(
            AiValidationException.class, () -> service.openView(ENV, openViewRequest(directory)));

    assertTrue(error.getMessage().contains("directory"));
    assertFalse(error.getMessage().contains(directory));
    assertNull(transport.command);
  }

  /** 意图：CHANGE_DIRECTORY 的目录校验同样在传输之前完成，保持与 OPEN_VIEW 同一事实源。 */
  @ParameterizedTest
  @MethodSource("invalidDirectories")
  void changeDirectoryRejectsInvalidDirectoryBeforeSending(String directory) {
    FakeTransport transport = new FakeTransport();
    transport.reply = IdeReply.ok(VIEW, "/tmp", null);
    EnvironmentIdeServiceImpl service =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5));

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () -> service.changeDirectory(ENV, VIEW, changeDirectoryRequest(directory)));

    assertTrue(error.getMessage().contains("directory"));
    assertNull(transport.command);
  }

  @Test
  void openViewAllowsMissingDirectory() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, null, "/daemon/workspaces/" + VIEW, null);

    EnvironmentIdeViewDTO dto =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
            .openView(ENV, openViewRequest(null));

    // 缺省目录是合法请求（由宿主校验 HOME 后决定），只是不携带目录字段。
    assertEquals(IdeAction.OPEN_VIEW, transport.command.action());
    assertNull(transport.command.directory());
    assertEquals(VIEW, dto.getViewId());
  }

  @Test
  void changeDirectoryFallsBackToRequestedValuesWhenReplyOmitsThem() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, null, null, null, null);

    EnvironmentIdeViewDTO dto =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
            .changeDirectory(ENV, VIEW, changeDirectoryRequest("/tmp"));

    // Daemon 回执缺省时沿用请求身份，不制造空 view/目录。
    assertEquals(VIEW, dto.getViewId());
    assertEquals("/tmp", dto.getDirectory());
  }

  @Test
  void daemonRejectionWithoutMessageUsesFixedFallbackText() {
    FakeTransport transport = new FakeTransport();
    transport.reply = IdeReply.failure(null);

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                    .focusTerminal(ENV, VIEW));
    assertTrue(error.getMessage().contains("IDE command was rejected"));
  }

  @Test
  void openViewFallsBackToRequestedViewIdWhenReplyOmitsIt() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, null, "/home/dev", "/daemon/workspaces/" + VIEW, null);

    EnvironmentIdeViewDTO dto =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
            .openView(ENV, openViewRequest("/home/dev"));

    assertEquals(VIEW, dto.getViewId());
  }

  @Test
  void openViewFailsClosedWhenDaemonReturnsBlankWorkspacePath() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", " ", null);

    // 没有受管 workspace 就没有可授权的边界，不能退回未受控路径。
    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () ->
            service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                .openView(ENV, openViewRequest("/home/dev")));
  }

  @Test
  void openViewFailsClosedWhenViewCapacityIsExhausted() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);
    InMemoryIdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();
    FakeViewAuthorization authorization = new FakeViewAuthorization();
    authorization.capacityExhausted = true;

    AiEnvironmentIdeUnavailableException error =
        assertThrows(
            AiEnvironmentIdeUnavailableException.class,
            () ->
                service(transport, bootstrap, Duration.ofSeconds(5), authorization)
                    .openView(ENV, openViewRequest("/home/dev")));

    assertTrue(error.getMessage().contains("capacity"));
    // 容量预检必须在任何 Daemon 副作用之前：不发送命令、不授权、不铸造入口票据。
    assertEquals(1, authorization.ensureCapacityCalls);
    assertNull(transport.command);
    assertNull(authorization.openedViewId);
    assertEquals(0, bootstrap.pendingCount());
  }

  @Test
  void openViewNotifiesListenerAndIssuesBootstrapTicket() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);
    InMemoryIdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();
    FakeViewAuthorization authorization = new FakeViewAuthorization();

    EnvironmentIdeViewDTO dto =
        service(transport, bootstrap, Duration.ofSeconds(5), authorization)
            .openView(ENV, openViewRequest("/home/dev"));

    // 授权跟随真实视图生命周期，并绑定命令进入传输时的连接代际。
    assertEquals(new DaemonConnectionIdentity(ENV, INSTANCE, LEASE), authorization.openedIdentity);
    assertEquals(VIEW, authorization.openedViewId);
    assertEquals("/daemon/workspaces/" + VIEW, authorization.openedWorkspace);
    IdeBootstrapTicketService.Ticket ticket =
        bootstrap.consume(tokenFrom(dto.getEntryUrl())).orElseThrow();
    assertEquals(authorization.openedIdentity, ticket.identity());
  }

  @Test
  void openViewFailsClosedWhenListenerRejectsOpenedView() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);
    InMemoryIdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();
    FakeViewAuthorization authorization = new FakeViewAuthorization();
    authorization.openedRejected = true;

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () ->
            service(transport, bootstrap, Duration.ofSeconds(5), authorization)
                .openView(ENV, openViewRequest("/home/dev")));

    // 授权未成立时不得伪造成功入口票据（否则未授权 workspace 仍可换 cookie）。
    assertEquals(0, bootstrap.pendingCount());
  }

  @Test
  void closeViewNotifiesListenerAndRevokesIssuedTicket() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);
    InMemoryIdeBootstrapTicketService bootstrap = new InMemoryIdeBootstrapTicketService();
    FakeViewAuthorization authorization = new FakeViewAuthorization();
    EnvironmentIdeServiceImpl service =
        service(transport, bootstrap, Duration.ofSeconds(5), authorization);

    String token = tokenFrom(service.openView(ENV, openViewRequest("/home/dev")).getEntryUrl());
    assertEquals(1, bootstrap.pendingCount());

    service.closeView(ENV, VIEW);

    assertEquals(IdeAction.CLOSE_VIEW, transport.command.action());
    assertEquals(new DaemonConnectionIdentity(ENV, INSTANCE, LEASE), authorization.closedIdentity);
    assertEquals(VIEW, authorization.closedViewId);
    // 关闭后旧票据必须失效，否则同一 viewId 重开时旧入口仍能换到 cookie。
    assertTrue(bootstrap.consume(token).isEmpty());
  }

  @Test
  void interruptedReplyWaitRestoresCallerInterruptAndAbandonsCommand() {
    FakeTransport transport = new FakeTransport();
    transport.leavePending = true;
    EnvironmentIdeServiceImpl service =
        service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(30));

    // 等待回执的线程已被中断：CompletableFuture.get 立即抛 InterruptedException，无需猜时序。
    Thread.currentThread().interrupt();
    try {
      AiEnvironmentIdeUnavailableException error =
          assertThrows(
              AiEnvironmentIdeUnavailableException.class, () -> service.closeView(ENV, VIEW));

      assertTrue(error.getMessage().contains("interrupted"));
      // 服务吞掉 InterruptedException 后必须恢复调用方的中断标记。
      assertTrue(Thread.currentThread().isInterrupted());
      // 中断等待与超时一样必须放弃在途命令，且恰好一次。
      assertEquals(List.of(transport.invocationId), transport.abandoned);
    } finally {
      // 清掉本测试设置的中断标记，避免泄漏到后续测试。
      Thread.interrupted();
    }
  }

  private static EnvironmentIdeOpenViewDTO openViewRequest(String directory) {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDirectory(directory);
    return request;
  }

  private static EnvironmentIdeChangeDirectoryDTO changeDirectoryRequest(String directory) {
    EnvironmentIdeChangeDirectoryDTO request = new EnvironmentIdeChangeDirectoryDTO();
    request.setDirectory(directory);
    return request;
  }

  private static String tokenFrom(String entryUrl) {
    return entryUrl.substring(entryUrl.indexOf("token=") + "token=".length());
  }

  /** 目录输入的 wire 非法集合：空白、超过 4096 字符、含控制字符。 */
  private static Stream<String> invalidDirectories() {
    return Stream.of("   ", "a".repeat(4097), "/tmp/\u0001");
  }

  /** 可编程 IDE 命令传输：记录发送的命令并立即（或延后）收敛，用于驱动控制服务的全部分支。 */
  private static final class FakeTransport implements IdeCommandTransport {

    IdeCommand command;
    EnvironmentId environment;
    UUID invocationId;
    IdeReply reply;
    RuntimeException requestFailure;
    boolean completeExceptionally;
    RuntimeException replyFailure;
    boolean leavePending;
    final List<UUID> abandoned = new ArrayList<>();

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand ideCommand) {
      if (requestFailure != null) {
        throw requestFailure;
      }
      this.environment = environmentId;
      this.command = ideCommand;
      this.invocationId = UUID.randomUUID();
      CompletableFuture<IdeReply> future = new CompletableFuture<>();
      if (!leavePending) {
        if (completeExceptionally) {
          future.completeExceptionally(replyFailure);
        } else {
          future.complete(reply);
        }
      }
      DaemonConnectionIdentity identity =
          new DaemonConnectionIdentity(environmentId, INSTANCE, LEASE);
      return new IdeRequest(identity, invocationId, future);
    }

    @Override
    public void abandonIde(UUID id) {
      abandoned.add(id);
    }

    @Override
    public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
      return Optional.of(new DaemonConnectionIdentity(environmentId, INSTANCE, LEASE));
    }
  }

  /** 可编程视图授权监听器：记录回调入参，并可模拟容量耗尽与授权失败。 */
  private static final class FakeViewAuthorization implements IdeViewAuthorizationListener {

    boolean capacityExhausted;
    boolean openedRejected;
    int ensureCapacityCalls;
    DaemonConnectionIdentity openedIdentity;
    String openedViewId;
    String openedWorkspace;
    DaemonConnectionIdentity closedIdentity;
    String closedViewId;

    @Override
    public void ensureViewCapacity(EnvironmentId environmentId) {
      ensureCapacityCalls++;
      if (capacityExhausted) {
        throw new IllegalStateException("view capacity is exhausted");
      }
    }

    @Override
    public void viewOpened(DaemonConnectionIdentity identity, String viewId, String workspacePath) {
      if (openedRejected) {
        throw new IllegalStateException("view is no longer authorizable");
      }
      openedIdentity = identity;
      openedViewId = viewId;
      openedWorkspace = workspacePath;
    }

    @Override
    public void viewClosed(DaemonConnectionIdentity identity, String viewId) {
      closedIdentity = identity;
      closedViewId = viewId;
    }
  }
}
