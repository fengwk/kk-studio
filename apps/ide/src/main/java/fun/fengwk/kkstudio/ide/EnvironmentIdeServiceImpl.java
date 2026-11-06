package fun.fengwk.kkstudio.ide;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewState;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayConstants;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

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

/**
 * IDE 视图控制服务实现：owner 在发任何命令前经事件节点核对 exact intent，然后通过既有 lease/READY 围栏下发一条 {@code IDE_COMMAND}。
 *
 * <p>绑定身份命令必须匹配 owner 缓存的 document+epoch，且缓存缺失/漂移时先用 {@code LIST_VIEWS} 重建再重判；旧 epoch 的关闭、目录与终端请求不得
 * 作用于新绑定。命令超时/断线映射为不可用；Daemon 的 VIEW_GONE 立即拒绝并撤销本地授权，其它拒绝保留安全文案。所有失败路径都不重放副作用。
 */
@Slf4j
public class EnvironmentIdeServiceImpl implements EnvironmentIdeService {

  /** 除普通关闭外，单条 IDE 命令的默认等待上限。 */
  public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

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
    IdeOpenMode mode = protocolMode(request.getMode());
    String directory = parseDirectory(request.getDirectory());
    requireIntent(eventNodeId, connectionId, documentId, environmentId, viewId, claimId);
    ensureCapacity(environmentId);
    IdeCommand.Builder commandBuilder =
        IdeCommand.newBuilder()
            .setAction(IdeAction.OPEN_VIEW)
            .setViewId(viewId.toString())
            .setDocumentId(documentId.toString())
            .setEventNodeId(eventNodeId.toString())
            .setConnectionId(connectionId.toString())
            .setClaimId(claimId.toString())
            .setMode(mode);
    if (directory != null) {
      commandBuilder.setDirectory(directory);
    }
    IdeCall result;
    try {
      result = call(environmentId, commandBuilder.build());
    } catch (IdeOutcomeUnknownException error) {
      // 结果不确定时只按原 claimId 读权威快照对账；绝不重发 OPEN_VIEW 或任何终端/目录副作用。
      return reconcileOpen(
          environmentId, viewId, documentId, eventNodeId, connectionId, claimId, error);
    }
    if (!result.reply().getOk()) {
      throw rejection(result.reply());
    }
    requireMatchingIdentity(result.reply(), viewId, documentId, eventNodeId, connectionId, claimId);
    if (!result.reply().hasBindingEpoch() || result.reply().getBindingEpoch() <= 0) {
      throw protocolFailure("daemon did not return a binding epoch");
    }
    long bindingEpoch = result.reply().getBindingEpoch();
    String confirmedDirectory = requirePath(result.reply().getDirectory(), "directory");
    String workspacePath = requirePath(result.reply().getWorkspacePath(), "workspacePath");
    ViewSnapshot view =
        new ViewSnapshot(
            new IdeViewBindingRegistry.Binding(
                result.identity(),
                viewId,
                documentId,
                eventNodeId,
                connectionId,
                claimId,
                bindingEpoch),
            confirmedDirectory,
            workspacePath,
            IdeViewState.OPEN);
    return publishOpen(view);
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
    List<ViewSnapshot> current;
    try {
      current = loadViews(environmentId);
    } catch (RuntimeException error) {
      unknown.addSuppressed(error);
      throw DomainException.unavailable("IDE_UNAVAILABLE", unknown.getMessage(), unknown);
    }
    ViewSnapshot view =
        current.stream()
            .filter(candidate -> candidate.binding().viewId().equals(viewId))
            .filter(candidate -> candidate.binding().documentId().equals(documentId))
            .filter(candidate -> candidate.binding().eventNodeId().equals(eventNodeId))
            .filter(candidate -> candidate.binding().connectionId().equals(connectionId))
            .filter(candidate -> candidate.binding().claimId().equals(claimId))
            .findFirst()
            .orElseThrow(
                () ->
                    DomainException.unavailable("IDE_UNAVAILABLE", unknown.getMessage(), unknown));
    requireIntent(eventNodeId, connectionId, documentId, environmentId, viewId, claimId);
    return publishOpen(view);
  }

  /** 落实本地授权、路由缓存与一次性入口票据；任何一步失败都不得留下可被命令命中的半绑定。 */
  private EnvironmentIdeViewDTO publishOpen(ViewSnapshot view) {
    IdeViewBindingRegistry.Binding binding = view.binding();
    recordAuthorized(view);
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
              view.workspacePath());
    } catch (RuntimeException error) {
      // 出票失败时回滚本地授权/路由；Daemon 视图留给其正常过期回收，不伪造成功入口。
      revokeLocal(binding);
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "environment IDE bootstrap ticket could not be issued", error);
    }
    EnvironmentIdeViewDTO dto = toDto(view);
    dto.setEntryUrl(
        IdeGatewayConstants.ROUTE_PREFIX
            + environmentId
            + "/"
            + viewId
            + IdeGatewayConstants.BOOTSTRAP_ENDPOINT
            + "?token="
            + ticket);
    return dto;
  }

  /** 授权与路由缓存只能成对发布；授权失败时该视图不得出现在任何后续命令的缓存中。 */
  private void recordAuthorized(ViewSnapshot view) {
    IdeViewBindingRegistry.Binding binding = view.binding();
    if (viewAuthorization != null) {
      try {
        viewAuthorization.viewOpened(
            binding.daemonIdentity(),
            binding.viewId().toString(),
            binding.bindingEpoch(),
            view.workspacePath());
      } catch (IllegalStateException error) {
        throw DomainException.unavailable(
            "IDE_UNAVAILABLE", "environment IDE view capacity is exhausted", error);
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
      throw DomainException.validation("environment", "directory must not be blank");
    }
    IdeViewBindingRegistry.Binding binding =
        requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCommand command =
        IdeCommand.newBuilder()
            .setAction(IdeAction.CHANGE_DIRECTORY)
            .setViewId(parsedViewId.toString())
            .setDocumentId(documentId.toString())
            .setBindingEpoch(bindingEpoch)
            .setDirectory(directory)
            .build();
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      throw rejection(result.reply());
    }
    requireReplyView(result.reply(), parsedViewId);
    String confirmed = requirePath(result.reply().getDirectory(), "directory");
    ViewSnapshot current = readBackBinding(environmentId, binding);
    EnvironmentIdeViewDTO dto = current == null ? toDto(binding) : toDto(current);
    dto.setDirectory(confirmed);
    return dto;
  }

  /**
   * 命令确认后回读 Daemon 快照刷新绑定缓存。
   *
   * <p>回读只用于刷新缓存与补充响应：回读本身失败时保留已确认的切换结果并记录告警，不能把成功的目录切换报成失败；回读成功但绑定已不在快照中， 说明视图已换代或关闭，仍按 {@code
   * VIEW_GONE} 拒绝。
   */
  private ViewSnapshot readBackBinding(
      EnvironmentId environmentId, IdeViewBindingRegistry.Binding binding) {
    List<ViewSnapshot> current;
    try {
      current = loadViews(environmentId);
    } catch (RuntimeException error) {
      log.warn("failed to read back IDE views after changing directory", error);
      return null;
    }
    return current.stream()
        .filter(candidate -> candidate.binding().equals(binding))
        .findFirst()
        .orElseThrow(EnvironmentIdeServiceImpl::viewGone);
  }

  @Override
  public void focusTerminal(
      EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request) {
    boundAction(environmentId, viewId, request, IdeAction.FOCUS_TERMINAL);
  }

  @Override
  public void collapseMobile(
      EnvironmentId environmentId, String viewId, EnvironmentIdeViewBindingDTO request) {
    boundAction(environmentId, viewId, request, IdeAction.COLLAPSE_MOBILE);
  }

  private void boundAction(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeViewBindingDTO request,
      IdeAction action) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID documentId = parseUuid(request.getDocumentId(), "documentId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCommand command =
        IdeCommand.newBuilder()
            .setAction(action)
            .setViewId(parsedViewId.toString())
            .setDocumentId(documentId.toString())
            .setBindingEpoch(bindingEpoch)
            .build();
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
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
    boolean discard = request.isDiscard();
    IdeViewBindingRegistry.Binding binding =
        requireBinding(environmentId, parsedViewId, documentId, bindingEpoch);
    IdeCommand command =
        IdeCommand.newBuilder()
            .setAction(IdeAction.CLOSE_VIEW)
            .setViewId(parsedViewId.toString())
            .setDocumentId(documentId.toString())
            .setBindingEpoch(bindingEpoch)
            .setDiscard(discard)
            .build();
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      if (isViewGone(result.reply())) {
        revokeLocal(binding);
      }
      throw rejection(result.reply());
    }
    requireReplyView(result.reply(), parsedViewId);
    revokeLocal(binding);
  }

  @Override
  public EnvironmentIdeDirectoryListingDTO listDirectories(
      EnvironmentId environmentId, String path) {
    Objects.requireNonNull(environmentId, "environmentId");
    String directory = parseDirectory(path);
    IdeCommand.Builder command = IdeCommand.newBuilder().setAction(IdeAction.LIST_DIRECTORIES);
    if (directory != null) {
      command.setDirectory(directory);
    }
    IdeResult reply = callConfirmed(environmentId, command.build()).reply();
    if (!reply.getOk()) {
      throw rejection(reply);
    }
    if (!reply.hasDirectoryListing()) {
      throw protocolFailure("daemon did not return a directory listing");
    }
    var listing = reply.getDirectoryListing();
    EnvironmentIdeDirectoryListingDTO dto = new EnvironmentIdeDirectoryListingDTO();
    dto.setPath(listing.getPath());
    dto.setParentPath(listing.hasParentPath() ? listing.getParentPath() : null);
    dto.setTruncated(listing.getTruncated());
    dto.setChildren(
        listing.getChildrenList().stream()
            .map(
                child -> {
                  EnvironmentIdeDirectoryListingDTO.Entry entry =
                      new EnvironmentIdeDirectoryListingDTO.Entry();
                  entry.setName(child.getName());
                  entry.setPath(child.getPath());
                  return entry;
                })
            .toList());
    return dto;
  }

  @Override
  public EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    List<ViewSnapshot> current = loadViews(environmentId);
    EnvironmentIdeViewsDTO dto = new EnvironmentIdeViewsDTO();
    dto.setViews(current.stream().map(EnvironmentIdeServiceImpl::toDto).toList());
    return dto;
  }

  private List<ViewSnapshot> loadViews(EnvironmentId environmentId) {
    IdeCommand command = IdeCommand.newBuilder().setAction(IdeAction.LIST_VIEWS).build();
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      throw rejection(result.reply());
    }
    List<IdeViewSummary> views = result.reply().getViewsList();
    List<ViewSnapshot> authorized = new ArrayList<>(views.size());
    for (IdeViewSummary view : views) {
      ViewSnapshot snapshot =
          new ViewSnapshot(
              new IdeViewBindingRegistry.Binding(
                  result.identity(),
                  parseUuid(view.getViewId(), "viewId"),
                  parseUuid(view.getDocumentId(), "documentId"),
                  parseUuid(view.getEventNodeId(), "eventNodeId"),
                  parseUuid(view.getConnectionId(), "connectionId"),
                  parseUuid(view.getClaimId(), "claimId"),
                  view.getBindingEpoch()),
              view.getDirectory(),
              view.getWorkspacePath(),
              view.getState());
      // 权威快照同样必须先取得本地授权才能进入路由缓存；容量失败时该视图不参与任何后续命令。
      if (authorizeForRouting(snapshot)) {
        authorized.add(snapshot);
      }
    }
    List<IdeViewBindingRegistry.Binding> current =
        authorized.stream().map(ViewSnapshot::binding).toList();
    revokeStaleAuthorizations(environmentId, current);
    bindings.replaceEnvironment(environmentId, current);
    return authorized;
  }

  /** 快照重建时对仍存在的视图补授权；失败只拒绝该视图，不影响其它视图与快照读取。 */
  private boolean authorizeForRouting(ViewSnapshot view) {
    if (viewAuthorization == null) {
      return true;
    }
    try {
      IdeViewBindingRegistry.Binding binding = view.binding();
      viewAuthorization.viewOpened(
          binding.daemonIdentity(),
          binding.viewId().toString(),
          binding.bindingEpoch(),
          view.workspacePath());
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
    } catch (DomainException error) {
      if (error.code() == DomainErrorCode.UNAVAILABLE && "IDE_UNAVAILABLE".equals(error.reason())) {
        throw error;
      }
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser IDE usage intent is unavailable", error);
    } catch (RuntimeException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser IDE usage intent is unavailable", error);
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
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "environment IDE view capacity is exhausted", error);
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
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "environment IDE channel is unavailable", error);
    }
    IdeResult reply;
    try {
      reply =
          command.getAction() == IdeAction.CLOSE_VIEW
                  && command.hasDiscard()
                  && !command.getDiscard()
              ? request.reply().get()
              : request.reply().get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

  private IdeCall callConfirmed(EnvironmentId environmentId, IdeCommand command) {
    try {
      return call(environmentId, command);
    } catch (IdeOutcomeUnknownException error) {
      throw DomainException.unavailable("IDE_UNAVAILABLE", error.getMessage(), error);
    }
  }

  private static void requireMatchingIdentity(
      IdeResult reply,
      UUID viewId,
      UUID documentId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId) {
    if (!reply.hasViewId()
        || !viewId.toString().equals(reply.getViewId())
        || !reply.hasDocumentId()
        || !documentId.toString().equals(reply.getDocumentId())
        || !reply.hasEventNodeId()
        || !eventNodeId.toString().equals(reply.getEventNodeId())
        || !reply.hasConnectionId()
        || !connectionId.toString().equals(reply.getConnectionId())
        || !reply.hasClaimId()
        || !claimId.toString().equals(reply.getClaimId())) {
      throw protocolFailure("daemon returned a different IDE view binding");
    }
  }

  private static void requireReplyView(IdeResult reply, UUID viewId) {
    if (!reply.hasViewId() || !viewId.toString().equals(reply.getViewId())) {
      throw protocolFailure("daemon returned a different IDE view");
    }
  }

  private static String requirePath(String value, String field) {
    if (value == null || value.isBlank()) {
      throw protocolFailure("daemon did not return " + field);
    }
    return value;
  }

  private static boolean isViewGone(IdeResult reply) {
    return reply.hasCode() && "VIEW_GONE".equals(reply.getCode());
  }

  private static DomainException rejection(IdeResult reply) {
    String message =
        !reply.hasMessage() || reply.getMessage().isBlank()
            ? "IDE command was rejected"
            : reply.getMessage();
    if (isViewGone(reply)) {
      return viewGone();
    }
    String code = reply.hasCode() ? reply.getCode() : "";
    if ("CANCELLED".equals(code)) {
      return DomainException.validationReason("IDE_CLOSE_CANCELLED", message);
    }
    if ("VIEW_IN_USE".equals(code)
        || "BUSY".equals(code)
        || "CLAIM_CONFLICT".equals(code)
        || "UNAVAILABLE".equals(code)
        || "CLEANUP_FAILED".equals(code)) {
      return DomainException.unavailable("IDE_UNAVAILABLE", message);
    }
    return DomainException.validation("environment", message);
  }

  private static DomainException viewGone() {
    return DomainException.unavailable(
        "IDE_UNAVAILABLE", "IDE view is gone; open a new view before retrying");
  }

  private static DomainException protocolFailure(String message) {
    return DomainException.unavailable("IDE_UNAVAILABLE", message);
  }

  private static EnvironmentIdeViewDTO toDto(ViewSnapshot view) {
    EnvironmentIdeViewDTO dto = toDto(view.binding());
    dto.setDirectory(view.directory());
    dto.setWorkspacePath(view.workspacePath());
    dto.setState(viewState(view.state()));
    return dto;
  }

  /** 只用已确认的绑定身份构造响应；Daemon 未回读到的 workspace 与 state 保持为空，不伪造。 */
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
    return dto;
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return Uuids.parseCanonical(value, field);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("environment", field + " must be a canonical UUID");
    }
  }

  private static UUID parseViewId(String value) {
    return parseUuid(value, "viewId");
  }

  private static long parseEpoch(String value) {
    try {
      return DecimalLongs.parsePositive(value, "bindingEpoch");
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          "environment", "bindingEpoch must be a canonical positive decimal");
    }
  }

  private static EnvironmentIdeViewState viewState(IdeViewState state) {
    return switch (state) {
      case OPEN -> EnvironmentIdeViewState.OPEN;
      case CLOSING -> EnvironmentIdeViewState.CLOSING;
      default -> throw protocolFailure("Daemon returned an unsupported IDE view state: " + state);
    };
  }

  private static IdeOpenMode protocolMode(EnvironmentIdeOpenMode mode) {
    if (mode == null) {
      throw DomainException.validation("environment", "mode must be create or resume");
    }
    return switch (mode) {
      case CREATE -> IdeOpenMode.CREATE;
      case RESUME -> IdeOpenMode.RESUME;
    };
  }

  public static String parseDirectory(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw DomainException.validation("environment", "directory must not be blank when present");
    }
    if (value.length() > 4096) {
      throw DomainException.validation("environment", "directory exceeds 4096 characters");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw DomainException.validation(
            "environment", "directory must not contain control characters");
      }
    }
    return value;
  }

  private record IdeCall(IdeResult reply, DaemonConnectionIdentity identity) {}

  /** 单次 Daemon 回执的权威视图状态；只把绑定身份留在路由缓存。 */
  private record ViewSnapshot(
      IdeViewBindingRegistry.Binding binding,
      String directory,
      String workspacePath,
      IdeViewState state) {}

  /** 命令可能已到达 Daemon 但未收到确定回执；只允许 claim 按原身份对账，其它副作用不得重放。 */
  private static final class IdeOutcomeUnknownException extends RuntimeException {

    private IdeOutcomeUnknownException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
