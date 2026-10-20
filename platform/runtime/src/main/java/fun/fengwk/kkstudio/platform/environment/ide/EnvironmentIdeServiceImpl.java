package fun.fengwk.kkstudio.platform.environment.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
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
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * IDE 视图控制服务实现：一次 IDE 命令 = 一条 {@code IDE_COMMAND} + 一条匹配 {@code IDE_RESULT}。
 *
 * <p>命令超时/断线/排队拒绝映射为 IDE 通道不可用（客户端可重试）；Daemon 明确拒绝（dirty、目录不可访问等）映射为校验错误并保留 Daemon
 * 文案。所有失败路径都不重放副作用，也不伪造成功结果；{@code OPEN_VIEW} 在任何副作用之前先校验 Independent Origin 配置（缺配置
 * fail-closed），并把入口凭据绑定到命令进入传输时的连接代际。
 */
public class EnvironmentIdeServiceImpl implements EnvironmentIdeService {

  /** 单条 IDE 命令的默认等待上限；超时后放弃在途登记（迟到回执被安静忽略）。 */
  public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

  public static final String IDE_ROUTE_PREFIX = "/environment-ide/";
  public static final String BOOTSTRAP_PATH = "__ide/bootstrap";

  private final IdeCommandTransport transport;
  private final IdeBootstrapTicketService bootstrapTickets;
  private final Duration commandTimeout;
  private final IdeViewAuthorizationListener viewAuthorization;

  public EnvironmentIdeServiceImpl(
      IdeCommandTransport transport, IdeBootstrapTicketService bootstrapTickets) {
    this(transport, bootstrapTickets, DEFAULT_COMMAND_TIMEOUT, null);
  }

  public EnvironmentIdeServiceImpl(
      IdeCommandTransport transport,
      IdeBootstrapTicketService bootstrapTickets,
      Duration commandTimeout) {
    this(transport, bootstrapTickets, commandTimeout, null);
  }

  public EnvironmentIdeServiceImpl(
      IdeCommandTransport transport,
      IdeBootstrapTicketService bootstrapTickets,
      Duration commandTimeout,
      IdeViewAuthorizationListener viewAuthorization) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
    this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    this.viewAuthorization = viewAuthorization;
    if (commandTimeout.isZero() || commandTimeout.isNegative()) {
      throw new IllegalArgumentException("commandTimeout must be positive");
    }
  }

  @Override
  public EnvironmentIdeViewDTO openView(
      EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    String viewId = parseViewId(request.getViewId());
    // 容量预检在任何 Daemon 副作用之前：视图/代际额度耗尽时显式失败，绝不先建 Native 视图再静默不授权。
    if (viewAuthorization != null) {
      try {
        viewAuthorization.ensureViewCapacity(environmentId);
      } catch (IllegalStateException error) {
        throw new AiEnvironmentIdeUnavailableException(
            "environment IDE view capacity is exhausted", error);
      }
    }
    IdeCommand command = directoryCommand(IdeAction.OPEN_VIEW, viewId, request.getDirectory());
    IdeCall result = call(environmentId, command);
    IdeReply reply = result.reply();
    EnvironmentIdeViewDTO dto =
        toDto(environmentId, reply.viewId() == null ? viewId : reply.viewId(), reply.directory());
    String workspacePath = requireWorkspace(reply);
    // 视图授权跟随真实视图生命周期：创建即授权，关闭即撤销（不使用空闲超时推断）。
    if (viewAuthorization != null) {
      try {
        viewAuthorization.viewOpened(result.identity(), dto.getViewId(), workspacePath);
      } catch (IllegalStateException error) {
        throw new AiEnvironmentIdeUnavailableException(
            "environment IDE view capacity is exhausted", error);
      }
    }
    // 入口凭据绑定命令的代际；租约失效/接管时由 host/router 依代际关闭并 revoke。
    String ticket = bootstrapTickets.issue(result.identity(), dto.getViewId(), workspacePath);
    dto.setEntryUrl(IDE_ROUTE_PREFIX + environmentId + "/" + BOOTSTRAP_PATH + "?token=" + ticket);
    return dto;
  }

  @Override
  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    String directory = request.getDirectory();
    if (directory == null || directory.isBlank()) {
      throw new AiValidationException("environment", "directory must not be blank");
    }
    IdeCommand command = directoryCommand(IdeAction.CHANGE_DIRECTORY, parsedViewId, directory);
    IdeReply reply = call(environmentId, command).reply();
    // 无需更换 iframe URL：entryUrl 保持 null，同 workspace 身份继续。
    return toDto(
        environmentId,
        reply.viewId() == null ? parsedViewId : reply.viewId(),
        reply.directory() == null ? directory : reply.directory());
  }

  @Override
  public void focusTerminal(EnvironmentId environmentId, String viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    call(environmentId, IdeCommand.view(IdeAction.FOCUS_TERMINAL, parseViewId(viewId), null));
  }

  @Override
  public void closeView(EnvironmentId environmentId, String viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    String parsedViewId = parseViewId(viewId);
    IdeCall result = call(environmentId, IdeCommand.view(IdeAction.CLOSE_VIEW, parsedViewId, null));
    if (viewAuthorization != null) {
      viewAuthorization.viewClosed(result.identity(), parsedViewId);
    }
    // 撤销该视图尚未消费的入口票据：否则视图关闭后（尤其同一 viewId 再次打开时）旧票据仍能换到 cookie。
    bootstrapTickets.revokeView(environmentId, parsedViewId);
  }

  private IdeCall call(EnvironmentId environmentId, IdeCommand command) {
    IdeRequest request;
    try {
      request = transport.requestIde(environmentId, command);
    } catch (EnvironmentCapabilityUnavailableException
        | EnvironmentCapabilityBusyException
        | EnvironmentCapabilitySendUncertainException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "environment IDE channel is unavailable", error);
    }
    IdeReply reply;
    try {
      reply = request.reply().get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      transport.abandonIde(request.invocationId());
      throw new AiEnvironmentIdeUnavailableException("environment IDE command timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      transport.abandonIde(request.invocationId());
      throw new AiEnvironmentIdeUnavailableException(
          "environment IDE command was interrupted", error);
    } catch (ExecutionException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "environment IDE command failed", error.getCause());
    }
    if (!reply.ok()) {
      String message = reply.message() == null ? "IDE command was rejected" : reply.message();
      throw new AiValidationException("environment", message);
    }
    return new IdeCall(reply, request.identity());
  }

  private static EnvironmentIdeViewDTO toDto(
      EnvironmentId environmentId, String viewId, String directory) {
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(viewId);
    dto.setEnvironmentId(environmentId.toString());
    dto.setDirectory(directory);
    return dto;
  }

  private static String requireWorkspace(IdeReply reply) {
    String workspacePath = reply.workspacePath();
    if (workspacePath == null || workspacePath.isBlank()) {
      throw new AiEnvironmentIdeUnavailableException(
          "daemon did not return a managed workspace path for the IDE view");
    }
    return workspacePath;
  }

  private static String parseViewId(String viewId) {
    try {
      return IdeCommand.validateViewId(viewId);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new AiValidationException("environment", "viewId must be a canonical UUID");
    }
  }

  /**
   * 构造携带目录的视图命令。目录来自外部输入，{@link IdeCommand} 的 wire 校验（空白/长度/控制字符）必须翻译为类型化校验错误，而不是把 {@link
   * IllegalArgumentException} 泄漏成未分类失败（HTTP 500）；错误只携带固定校验原因，不回显输入值。
   */
  private static IdeCommand directoryCommand(IdeAction action, String viewId, String directory) {
    try {
      return IdeCommand.view(action, viewId, directory);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environment", error.getMessage());
    }
  }

  private record IdeCall(IdeReply reply, IdeSessionIdentity identity) {}
}
