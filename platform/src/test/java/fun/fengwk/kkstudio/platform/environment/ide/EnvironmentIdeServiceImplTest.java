package fun.fengwk.kkstudio.platform.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
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

/**
 * 意图：IDE 控制服务必须把 view 请求精确翻译为对应 action 的 IDE 命令，不伪造结果：Daemon 拒绝原样上报，通道不可用/超时 明确失败并放弃在途命令；OPEN_VIEW
 * 还须铸造一次性 bootstrap 入口，缺 Origin 配置时 fail-closed。
 */
class EnvironmentIdeServiceImplTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String INSTANCE = "33333333-3333-3333-3333-333333333333";
  private static final UUID LEASE = new UUID(0L, 77L);

  private final IdeOriginProperties originProperties = new IdeOriginProperties();

  private EnvironmentIdeServiceImpl service(
      FakeTransport transport, IdeBootstrapTicketService bootstrap, Duration timeout) {
    return new EnvironmentIdeServiceImpl(transport, originProperties, bootstrap, timeout);
  }

  @Test
  void openViewSendsCommandAndReturnsBootstrapEntry() {
    originProperties.setOriginTemplate("https://{environmentId}.ide.example.com");
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
    assertTrue(
        dto.getEntryUrl().startsWith("https://" + ENV + ".ide.example.com/__ide/bootstrap?token="));
    // entryUrl 内的票据一次性绑定 workspace 路径。
    String token =
        dto.getEntryUrl().substring(dto.getEntryUrl().indexOf("token=") + "token=".length());
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
  void openViewFailsClosedWhenOriginTemplateMissing() {
    FakeTransport transport = new FakeTransport();
    transport.reply = new IdeReply(true, VIEW, "/home/dev", "/daemon/workspaces/" + VIEW, null);

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () ->
            service(transport, new InMemoryIdeBootstrapTicketService(), Duration.ofSeconds(5))
                .openView(ENV, openViewRequest(null)));
  }

  @Test
  void openViewFailsClosedWhenDaemonOmitsWorkspacePath() {
    originProperties.setOriginTemplate("https://{environmentId}.ide.example.com");
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

  private static EnvironmentIdeOpenViewDTO openViewRequest(String directory) {
    EnvironmentIdeOpenViewDTO request = new EnvironmentIdeOpenViewDTO();
    request.setViewId(VIEW);
    request.setDirectory(directory);
    return request;
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
      IdeSessionIdentity identity = new IdeSessionIdentity(environmentId, INSTANCE, LEASE);
      return new IdeRequest(identity, invocationId, future);
    }

    @Override
    public void abandonIde(UUID id) {
      abandoned.add(id);
    }

    @Override
    public Optional<IdeSessionIdentity> ideSessionIdentity(EnvironmentId environmentId) {
      return Optional.of(new IdeSessionIdentity(environmentId, INSTANCE, LEASE));
    }
  }
}
