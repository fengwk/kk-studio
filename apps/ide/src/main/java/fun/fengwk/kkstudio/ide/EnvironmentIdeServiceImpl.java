package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.ide.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;
import fun.fengwk.kkstudio.ide.protocol.IdeViewState;
import fun.fengwk.kkstudio.ide.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.error.AiDomainException;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * IDE 视图控制服务实现：owner 在发任何命令前经事件节点核对 exact intent，然后通过既有 lease/READY 围栏下发一条 {@code IDE_COMMAND}。
 *
 * <p>绑定身份命令必须匹配 owner 缓存的 document+epoch，且缓存缺失/漂移时先用 {@code LIST_VIEWS} 重建再重判；旧 epoch 的关闭、目录与终端请求不得
 * 作用于新绑定。命令超时/断线映射为不可用；Daemon 的 VIEW_GONE 立即拒绝并撤销本地授权，其它拒绝保留安全文案。所有失败路径都不重放副作用。
 */
public class EnvironmentIdeServiceImpl implements EnvironmentIdeService {

  /** 单条 IDE 命令的默认等待上限；超时后放弃在途登记（迟到回执被安静忽略）。 */
  public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

  public static final String IDE_ROUTE_PREFIX = "/environment-ide/";
  public static final String BOOTSTRAP_PATH = "__ide/bootstrap";
  public static final String SESSION_PATH = "__ide/session";

  private final IdeCommandTransport transport;
  private final IdeBootstrapTicketService bootstrapTickets;
  private final IdeBrowserUseGateway browserUse;
  private final IdeViewBindingRegistry bindings;
  private final Duration commandTimeout;
  private final IdeViewAuthorizationListener viewAuthorization;

  public EnvironmentIdeServiceImpl(
      IdeCommandTransport transport,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserUseGateway browserUse,
      IdeViewBindingRegistry bindings,
      Duration commandTimeout,
      IdeViewAuthorizationListener viewAuthorization) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
    this.browserUse = Objects.requireNonNull(browserUse, "browserUse");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
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
    UUID viewId = parseUuid(request.getViewId(), "viewId");
    UUID documentId = parseUuid(request.getDocumentId(), "documentId");
    UUID eventNodeId = parseUuid(request.getEventNodeId(), "eventNodeId");
    UUID connectionId = parseUuid(request.getConnectionId(), "connectionId");
    UUID claimId = parseUuid(request.getClaimId(), "claimId");
    IdeOpenMode mode = parseMode(request.getMode());
    String directory = parseDirectory(request.getDirectory());
    requireIntent(eventNodeId, connectionId, documentId, environmentId, viewId, claimId);
    ensureCapacity(environmentId);
    IdeCommand command =
        command(
            () ->
                IdeCommand.openView(
                    viewId.toString(),
                    documentId.toString(),
                    eventNodeId.toString(),
                    connectionId.toString(),
                    claimId.toString(),
                    mode,
                    directory));
    IdeCall result;
    try {
      result = call(environmentId, command);
    } catch (IdeOutcomeUnknownException error) {
      // 结果不确定时只按原 claimId 读权威快照对账；绝不重发 OPEN_VIEW 或任何终端/目录副作用。
      return reconcileOpen(
          environmentId, viewId, documentId, eventNodeId, connectionId, claimId, error);
    }
    if (!result.reply().ok()) {
      throw rejection(result.reply());
    }
    requireMatchingIdentity(result.reply(), viewId, documentId, eventNodeId, connectionId, claimId);
    Long bindingEpoch = result.reply().bindingEpoch();
    if (bindingEpoch == null || bindingEpoch <= 0) {
      throw protocolFailure("daemon did not return a binding epoch");
    }
    String confirmedDirectory = requirePath(result.reply().directory(), "directory");
    String workspacePath = requirePath(result.reply().workspacePath(), "workspacePath");
    IdeViewBindingRegistry.Binding binding =
        new IdeViewBindingRegistry.Binding(
            result.identity(),
            viewId,
            documentId,
            eventNodeId,
            connectionId,
            claimId,
            bindingEpoch,
            confirmedDirectory,
            workspacePath,
            IdeViewState.OPEN);
    return publishOpen(binding);
  }

  /**
   * 原 claim 结果未知时通过 {@code LIST_VIEWS} 对账：只有 view/document/eventNode/connection/claim 全部一致才认领。
   *
   * <p>快照缺失、参数漂移或浏览器意图已消失都保持原结果不确定错误，不把猜测当成成功，也不重放 claim 本身。
   */
  private EnvironmentIdeViewDTO reconcileOpen(
      EnvironmentId environmentId,
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      IdeOutcomeUnknownException unknown) {
    List<IdeViewBindingRegistry.Binding> current;
    try {
      current = loadViews(environmentId);
    } catch (RuntimeException error) {
      unknown.addSuppressed(error);
      throw unknown;
    }
    IdeViewBindingRegistry.Binding binding =
        current.stream()
            .filter(candidate -> candidate.viewId().equals(viewId))
            .filter(candidate -> candidate.documentId().equals(documentId))
            .filter(candidate -> candidate.eventNodeId().equals(eventNodeId))
            .filter(candidate -> candidate.connectionId().equals(connectionId))
            .filter(candidate -> candidate.claimId().equals(claimId))
            .findFirst()
            .orElseThrow(() -> unknown);
    requireIntent(eventNodeId, connectionId, documentId, environmentId, viewId, claimId);
    return publishOpen(binding);
  }

  /** 落实本地授权、路由缓存与一次性入口票据；任何一步失败都不得留下可被命令命中的半绑定。 */
  private EnvironmentIdeViewDTO publishOpen(IdeViewBindingRegistry.Binding binding) {
    recordAuthorized(binding);
    UUID viewId = binding.viewId();
    EnvironmentId environmentId = binding.environmentId();
    bootstrapTickets.revokeView(environmentId, viewId.toString());
    String ticket;
    try {
      ticket =
          bootstrapTickets.issue(
              binding.daemonIdentity(),
              viewId.toString(),
              binding.bindingEpoch(),
              binding.workspacePath());
    } catch (RuntimeException error) {
      // 出票失败时回滚本地授权/路由；Daemon 视图留给其正常过期回收，不伪造成功入口。
      revokeLocal(binding);
      throw new AiEnvironmentIdeUnavailableException(
          "environment IDE bootstrap ticket could not be issued", error);
    }
    EnvironmentIdeViewDTO dto = toDto(binding);
    dto.setEntryUrl(
        IDE_ROUTE_PREFIX
            + environmentId
            + "/"
            + viewId
            + "/"
            + BOOTSTRAP_PATH
            + "?token="
            + ticket);
    return dto;
  }

  /** 授权与路由缓存只能成对发布；授权失败时该视图不得出现在任何后续命令的缓存中。 */
  private void recordAuthorized(IdeViewBindingRegistry.Binding binding) {
    if (viewAuthorization != null) {
      try {
        viewAuthorization.viewOpened(
            binding.daemonIdentity(),
            binding.viewId().toString(),
            binding.bindingEpoch(),
            binding.workspacePath());
      } catch (IllegalStateException error) {
        throw new AiEnvironmentIdeUnavailableException(
            "environment IDE view capacity is exhausted", error);
      }
    }
    bindings.record(binding);
  }

  @Override
  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID documentId = parseUuid(request.getDocumentId(), "documentId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    String directory = parseDirectory(request.getDirectory());
    if (directory == null) {
      throw new AiValidationException("environment", "directory must not be blank");
    }
    IdeViewBindingRegistry.Binding binding =
        requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCommand command =
        command(
            () ->
                IdeCommand.changeDirectory(
                    parsedViewId.toString(), documentId.toString(), bindingEpoch, directory));
    IdeCall result = call(environmentId, command);
    if (!result.reply().ok()) {
      throw rejection(result.reply());
    }
    requireReplyView(result.reply(), parsedViewId);
    String confirmed = result.reply().directory() == null ? directory : result.reply().directory();
    if (!bindings.updateDirectory(
        environmentId, parsedViewId, documentId, bindingEpoch, confirmed)) {
      throw viewGone();
    }
    IdeViewBindingRegistry.Binding current =
        bindings
            .find(environmentId, parsedViewId)
            .filter(candidate -> candidate.matches(documentId, bindingEpoch))
            .orElseThrow(EnvironmentIdeServiceImpl::viewGone);
    return toDto(current);
  }

  @Override
  public void focusTerminal(
      EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID documentId = parseUuid(request.getDocumentId(), "documentId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCall result =
        call(
            environmentId,
            command(
                () ->
                    IdeCommand.focusTerminal(
                        parsedViewId.toString(), documentId.toString(), bindingEpoch)));
    if (!result.reply().ok()) {
      throw rejection(result.reply());
    }
    requireReplyView(result.reply(), parsedViewId);
  }

  @Override
  public void closeView(
      EnvironmentId environmentId, String viewId, EnvironmentIdeCloseViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID documentId = parseUuid(request.getDocumentId(), "documentId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    boolean discard = requireDiscard(request.getDiscard());
    IdeViewBindingRegistry.Binding binding =
        requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCall result =
        call(
            environmentId,
            command(
                () ->
                    IdeCommand.closeView(
                        parsedViewId.toString(), documentId.toString(), bindingEpoch, discard)));
    if (!result.reply().ok()) {
      if (isViewGone(result.reply())) {
        revokeLocal(binding);
      }
      throw rejection(result.reply());
    }
    requireReplyView(result.reply(), parsedViewId);
    revokeLocal(binding);
  }

  @Override
  public void detachView(
      EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID documentId = parseUuid(request.getDocumentId(), "documentId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    IdeViewBindingRegistry.Binding binding =
        requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCall result =
        call(
            environmentId,
            command(
                () ->
                    IdeCommand.detachView(
                        parsedViewId.toString(), documentId.toString(), bindingEpoch)));
    if (!result.reply().ok()) {
      if (isViewGone(result.reply())) {
        revokeLocal(binding);
      }
      throw rejection(result.reply());
    }
    requireReplyView(result.reply(), parsedViewId);
    // detach 冻结该 epoch 的使用证明：本地不得再允许旧 cookie/旧请求进入；视图资源在 Daemon 恢复窗口内保留。
    revokeLocal(binding);
  }

  @Override
  public EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    List<IdeViewBindingRegistry.Binding> current = loadViews(environmentId);
    EnvironmentIdeViewsDTO dto = new EnvironmentIdeViewsDTO();
    dto.setViews(current.stream().map(EnvironmentIdeServiceImpl::toDto).toList());
    return dto;
  }

  private List<IdeViewBindingRegistry.Binding> loadViews(EnvironmentId environmentId) {
    IdeCall result = call(environmentId, IdeCommand.listViews());
    if (!result.reply().ok()) {
      throw rejection(result.reply());
    }
    List<IdeViewSummary> views = result.reply().views();
    if (views == null) {
      throw protocolFailure("daemon did not return a view list");
    }
    List<IdeViewBindingRegistry.Binding> authorized = new ArrayList<>(views.size());
    for (IdeViewSummary view : views) {
      IdeViewBindingRegistry.Binding binding =
          new IdeViewBindingRegistry.Binding(
              result.identity(),
              view.viewId(),
              view.documentId(),
              view.eventNodeId(),
              view.connectionId(),
              view.claimId(),
              view.bindingEpoch(),
              view.directory(),
              view.workspacePath(),
              view.state());
      // 权威快照同样必须先取得本地授权才能进入路由缓存；容量失败时该视图不参与任何后续命令。
      if (authorizeForRouting(binding)) {
        authorized.add(binding);
      }
    }
    revokeStaleAuthorizations(environmentId, authorized);
    return bindings.replaceEnvironment(environmentId, authorized);
  }

  /** 快照重建时对仍存在的视图补授权；失败只拒绝该视图，不影响其它视图与快照读取。 */
  private boolean authorizeForRouting(IdeViewBindingRegistry.Binding binding) {
    if (viewAuthorization == null) {
      return true;
    }
    try {
      viewAuthorization.viewOpened(
          binding.daemonIdentity(),
          binding.viewId().toString(),
          binding.bindingEpoch(),
          binding.workspacePath());
      return true;
    } catch (IllegalStateException error) {
      return false;
    }
  }

  /** 权威列表缺少的视图或 epoch 已换代时，撤销旧会话；新绑定自己的会话不受影响。只比对 Daemon 实际返回的 view+epoch。 */
  private void revokeStaleAuthorizations(
      EnvironmentId environmentId, List<IdeViewBindingRegistry.Binding> current) {
    if (viewAuthorization == null) {
      return;
    }
    Map<UUID, Long> epochs = new HashMap<>();
    for (IdeViewBindingRegistry.Binding binding : current) {
      epochs.put(binding.viewId(), binding.bindingEpoch());
    }
    for (IdeViewBindingRegistry.Binding existing : bindings.listEnvironment(environmentId)) {
      Long currentEpoch = epochs.get(existing.viewId());
      if (currentEpoch == null || currentEpoch != existing.bindingEpoch()) {
        viewAuthorization.viewClosed(
            existing.daemonIdentity(), existing.viewId().toString(), existing.bindingEpoch());
        bootstrapTickets.revokeView(environmentId, existing.viewId().toString());
      }
    }
  }

  private IdeViewBindingRegistry.Binding requireBinding(
      EnvironmentId environmentId, UUID viewId, UUID documentId, long bindingEpoch) {
    Optional<IdeViewBindingRegistry.Binding> cached = bindings.find(environmentId, viewId);
    IdeViewBindingRegistry.Binding binding =
        cached
            .filter(candidate -> candidate.matches(documentId, bindingEpoch))
            .filter(this::isCurrentGeneration)
            .orElse(null);
    if (binding == null) {
      // 本地缓存缺失/漂移：以 Daemon 的 LIST_VIEWS 为权威重建后再判定；旧 epoch 仍不匹配即拒绝。
      loadViews(environmentId);
      binding =
          bindings
              .find(environmentId, viewId)
              .filter(candidate -> candidate.matches(documentId, bindingEpoch))
              .orElseThrow(EnvironmentIdeServiceImpl::viewGone);
    }
    requireIntent(
        binding.eventNodeId(),
        binding.connectionId(),
        binding.documentId(),
        environmentId,
        binding.viewId(),
        binding.claimId());
    return binding;
  }

  private void requireIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID documentId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    boolean matched;
    try {
      matched =
          browserUse.findExactIntent(
              eventNodeId, connectionId, documentId, environmentId, viewId, claimId);
    } catch (AiEnvironmentIdeUnavailableException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "browser IDE usage intent is unavailable", error);
    }
    if (!matched) {
      throw viewGone();
    }
  }

  private void ensureCapacity(EnvironmentId environmentId) {
    if (viewAuthorization == null) {
      return;
    }
    try {
      viewAuthorization.ensureViewCapacity(environmentId);
    } catch (IllegalStateException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "environment IDE view capacity is exhausted", error);
    }
  }

  private void revokeLocal(IdeViewBindingRegistry.Binding binding) {
    bindings.removeIfMatches(
        binding.environmentId(), binding.viewId(), binding.documentId(), binding.bindingEpoch());
    if (viewAuthorization != null) {
      viewAuthorization.viewClosed(
          binding.daemonIdentity(), binding.viewId().toString(), binding.bindingEpoch());
    }
    bootstrapTickets.revokeView(binding.environmentId(), binding.viewId().toString());
  }

  /** 缓存绑定必须仍属于当前 READY Daemon 代际；实例接管后旧 epoch 立即失效。 */
  private boolean isCurrentGeneration(IdeViewBindingRegistry.Binding binding) {
    return transport
        .connectionIdentity(binding.environmentId())
        .map(binding.daemonIdentity()::equals)
        .orElse(false);
  }

  private IdeCall call(EnvironmentId environmentId, IdeCommand command) {
    IdeRequest request;
    try {
      request = transport.requestIde(environmentId, command);
    } catch (EnvironmentCapabilitySendUncertainException error) {
      throw new IdeOutcomeUnknownException("environment IDE command outcome is uncertain", error);
    } catch (EnvironmentCapabilityUnavailableException | EnvironmentCapabilityBusyException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "environment IDE channel is unavailable", error);
    }
    IdeReply reply;
    try {
      reply = request.reply().get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      transport.abandonIde(request.invocationId());
      throw new IdeOutcomeUnknownException("environment IDE command timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      transport.abandonIde(request.invocationId());
      throw new IdeOutcomeUnknownException("environment IDE command was interrupted", error);
    } catch (ExecutionException error) {
      throw new IdeOutcomeUnknownException(
          "environment IDE command failed without a confirmed outcome", error.getCause());
    }
    return new IdeCall(reply, request.identity());
  }

  private static void requireMatchingIdentity(
      IdeReply reply,
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId) {
    if (!viewId.equals(reply.viewId())
        || !documentId.equals(reply.documentId())
        || !eventNodeId.equals(reply.eventNodeId())
        || !connectionId.equals(reply.connectionId())
        || !claimId.equals(reply.claimId())) {
      throw protocolFailure("daemon returned a different IDE view binding");
    }
  }

  private static void requireReplyView(IdeReply reply, UUID viewId) {
    if (!viewId.equals(reply.viewId())) {
      throw protocolFailure("daemon returned a different IDE view");
    }
  }

  private static String requirePath(String value, String field) {
    if (value == null || value.isBlank()) {
      throw protocolFailure("daemon did not return " + field);
    }
    return value;
  }

  private static boolean isViewGone(IdeReply reply) {
    return "VIEW_GONE".equals(reply.code());
  }

  private static AiDomainException rejection(IdeReply reply) {
    String message =
        reply.message() == null || reply.message().isBlank()
            ? "IDE command was rejected"
            : reply.message();
    if (isViewGone(reply)) {
      return viewGone();
    }
    if ("VIEW_IN_USE".equals(reply.code())
        || "BUSY".equals(reply.code())
        || "CLAIM_CONFLICT".equals(reply.code())
        || "UNAVAILABLE".equals(reply.code())
        || "CLEANUP_FAILED".equals(reply.code())) {
      return new AiEnvironmentIdeUnavailableException(message);
    }
    return new AiValidationException("environment", message);
  }

  private static AiEnvironmentIdeUnavailableException viewGone() {
    return new AiEnvironmentIdeUnavailableException(
        "IDE view is gone; open a new view before retrying");
  }

  private static AiEnvironmentIdeUnavailableException protocolFailure(String message) {
    return new AiEnvironmentIdeUnavailableException(message);
  }

  private static EnvironmentIdeViewDTO toDto(IdeViewBindingRegistry.Binding binding) {
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(binding.viewId().toString());
    dto.setEnvironmentId(binding.environmentId().toString());
    dto.setDocumentId(binding.documentId().toString());
    dto.setEventNodeId(binding.eventNodeId().toString());
    dto.setConnectionId(binding.connectionId().toString());
    dto.setClaimId(binding.claimId().toString());
    dto.setDaemonInstanceId(binding.daemonIdentity().daemonInstanceId());
    dto.setBindingEpoch(Long.toString(binding.bindingEpoch()));
    dto.setDirectory(binding.directory());
    dto.setWorkspacePath(binding.workspacePath());
    dto.setState(binding.state().name());
    return dto;
  }

  private static IdeCommand command(Supplier<IdeCommand> supplier) {
    try {
      return supplier.get();
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environment", error.getMessage());
    }
  }

  private static UUID parseUuid(String value, String field) {
    UUID parsed;
    try {
      parsed = UUID.fromString(Objects.requireNonNull(value, field));
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new AiValidationException("environment", field + " must be a canonical UUID");
    }
    if (!parsed.toString().equals(value)) {
      throw new AiValidationException("environment", field + " must be a canonical lowercase UUID");
    }
    return parsed;
  }

  private static UUID parseViewId(String value) {
    return parseUuid(value, "viewId");
  }

  private static long parseEpoch(String value) {
    try {
      return IdeCommand.parseBindingEpoch(value);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          "environment", "bindingEpoch must be a canonical positive decimal");
    }
  }

  private static IdeOpenMode parseMode(String value) {
    try {
      return IdeOpenMode.fromWire(value);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new AiValidationException("environment", "mode must be create or resume");
    }
  }

  private static String parseDirectory(String value) {
    try {
      return IdeCommand.validateOptionalDirectory(value);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environment", error.getMessage());
    }
  }

  private static boolean requireDiscard(Boolean value) {
    if (value == null) {
      throw new AiValidationException("environment", "discard is required");
    }
    return value;
  }

  private record IdeCall(IdeReply reply, DaemonConnectionIdentity identity) {}

  /** 命令可能已到达 Daemon 但未收到确定回执；只允许 claim 按原身份对账，其它副作用不得重放。 */
  private static final class IdeOutcomeUnknownException
      extends AiEnvironmentIdeUnavailableException {

    private IdeOutcomeUnknownException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
