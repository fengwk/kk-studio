package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeBindings.Binding;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayConstants;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * IDE 视图控制服务实现：owner 在发任何命令前经事件节点核对 exact intent，然后通过既有 lease/READY 围栏下发一条 {@code IDE_COMMAND}。
 *
 * <p>所有可见状态只认 Daemon 返回的完整 {@code {revision, views}} 快照；Backend 只保留一个不可变高 revision 快照缓存。更高 revision
 * 原子替换，更低 revision 不生效，旧实例或旧 epoch 的回执、票据与授权清理都不能覆盖新绑定。绑定查询、容量、bootstrap 与数据面授权读取同一快照； 浏览器会话登记只保存
 * cookie。命令发送不确定时只返回 {@code IDE_OUTCOME_UNKNOWN}，仅 OPEN 按原 claim 纯读对账。
 */
public class EnvironmentIdeServiceImpl implements EnvironmentIdeService {

  /** 除普通关闭外，单条 IDE 命令的默认等待上限。 */
  public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

  private final IdeCommandTransport transport;
  private final IdeBootstrapTicketService bootstrapTickets;
  private final IdeCommandCaller caller;
  private final IdeSnapshots snapshotBoundary;
  private final IdeBindings bindings;

  public EnvironmentIdeServiceImpl(
      IdeCommandTransport transport,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserUseGateway browserUse,
      IdeViewSnapshotStore snapshots,
      Duration commandTimeout) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
    IdeBrowserUseGateway requiredBrowserUse = Objects.requireNonNull(browserUse, "browserUse");
    IdeViewSnapshotStore requiredSnapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.caller = new IdeCommandCaller(transport, commandTimeout);
    this.snapshotBoundary =
        new IdeSnapshots(caller, transport, requiredSnapshots, bootstrapTickets);
    this.bindings = new IdeBindings(transport, requiredBrowserUse, snapshotBoundary);
  }

  @Override
  public EnvironmentIdeViewDTO openView(
      EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID viewId = IdeInputs.requireViewId(request.getViewId());
    UUID browserSessionId =
        IdeUuids.requireInput(request.getBrowserSessionId(), "browserSessionId");
    UUID eventNodeId = IdeUuids.requireInput(request.getEventNodeId(), "eventNodeId");
    UUID connectionId = IdeUuids.requireInput(request.getConnectionId(), "connectionId");
    UUID claimId = IdeUuids.requireInput(request.getClaimId(), "claimId");
    EnvironmentIdeOpenMode requestMode = request.getMode();
    if (requestMode == null) {
      throw DomainException.validation("environment", "mode must be create or resume");
    }
    IdeOpenMode mode = IdeProtocol.protocolMode(requestMode);
    long expectedBindingEpoch =
        IdeInputs.requireOpenEpoch(requestMode, request.getExpectedBindingEpoch());
    String directory = IdeInputs.requireDirectory(request.getDirectory());

    DaemonConnectionIdentity identity = bindings.requireReady(environmentId);
    String expectedDaemonInstanceId =
        IdeInputs.requireCurrentInstance(
            request.getExpectedDaemonInstanceId(),
            "expectedDaemonInstanceId is required for " + requestMode.wireValue(),
            identity);
    bindings.requireIntent(
        eventNodeId, connectionId, browserSessionId, environmentId, viewId, claimId);

    IdeCall result;
    try {
      result =
          caller.call(
              environmentId,
              openCommand(
                  mode,
                  viewId,
                  browserSessionId,
                  eventNodeId,
                  connectionId,
                  claimId,
                  expectedBindingEpoch,
                  expectedDaemonInstanceId,
                  directory));
    } catch (IdeCommandCaller.IdeOutcomeUnknownException error) {
      // 只按原 claimId 纯读对账；绝不重发 OPEN_VIEW 或任何终端/目录副作用。
      return reconcileOpen(
          environmentId,
          viewId,
          browserSessionId,
          eventNodeId,
          connectionId,
          claimId,
          identity,
          error);
    }
    if (!result.reply().getOk()) {
      snapshotBoundary.acceptFailureSnapshot(result);
      throw IdeProtocol.rejection(result.reply());
    }
    IdeViewSnapshotStore.Snapshot snapshot = snapshotBoundary.requireReplySnapshot(result);
    IdeViewSnapshotStore.View view =
        requireOpenedView(snapshot, viewId, browserSessionId, eventNodeId, connectionId, claimId);
    return publishOpen(identity, view);
  }

  private static IdeCommand openCommand(
      IdeOpenMode mode,
      UUID viewId,
      UUID browserSessionId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      long expectedBindingEpoch,
      String expectedDaemonInstanceId,
      String directory) {
    IdeCommand.Builder commandBuilder =
        IdeCommand.newBuilder()
            .setAction(IdeAction.OPEN_VIEW)
            .setViewId(viewId.toString())
            .setBrowserSessionId(browserSessionId.toString())
            .setEventNodeId(eventNodeId.toString())
            .setConnectionId(connectionId.toString())
            .setClaimId(claimId.toString())
            .setMode(mode)
            .setExpectedBindingEpoch(expectedBindingEpoch)
            .setExpectedDaemonInstanceId(expectedDaemonInstanceId);
    if (directory != null) {
      commandBuilder.setDirectory(directory);
    }
    return commandBuilder.build();
  }

  /**
   * 原 claim 结果未知时通过公开的纯读路径对账：只有 view/browserSession/eventNode/connection/claim 全部一致才认领。
   *
   * <p>快照不存在 view 是有效 absent 事实，但不是命令未执行的证明，因此保持 IDE_OUTCOME_UNKNOWN；实例已换代或出现更新的绑定则返回相应结构化原因。
   */
  private EnvironmentIdeViewDTO reconcileOpen(
      EnvironmentId environmentId,
      UUID viewId,
      UUID browserSessionId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      DaemonConnectionIdentity sentIdentity,
      IdeCommandCaller.IdeOutcomeUnknownException unknown) {
    try {
      Optional<DaemonConnectionIdentity> current = transport.connectionIdentity(environmentId);
      if (current.isEmpty()) {
        throw unknown;
      }
      if (!current.get().equals(sentIdentity)) {
        throw IdeProtocol.viewGone("environment daemon instance has been replaced");
      }
      EnvironmentIdeReadViewDTO read = new EnvironmentIdeReadViewDTO();
      read.setBrowserSessionId(browserSessionId.toString());
      read.setEventNodeId(eventNodeId.toString());
      read.setConnectionId(connectionId.toString());
      read.setClaimId(claimId.toString());
      read.setExpectedDaemonInstanceId(sentIdentity.daemonInstanceId());
      EnvironmentIdeViewSnapshotDTO snapshot = readView(environmentId, viewId.toString(), read);
      EnvironmentIdeViewDTO view = snapshot.getView();
      if (view == null) {
        throw unknown;
      }
      if (!browserSessionId.toString().equals(view.getBrowserSessionId())) {
        throw IdeProtocol.viewOwnedByOther();
      }
      if (!eventNodeId.toString().equals(view.getEventNodeId())
          || !connectionId.toString().equals(view.getConnectionId())
          || !claimId.toString().equals(view.getClaimId())) {
        throw IdeProtocol.staleBinding(
            "IDE binding changed before the unknown OPEN_VIEW outcome was read");
      }
      long bindingEpoch = IdeProtocol.requirePositiveEpoch(view.getBindingEpoch());
      String confirmedDirectory = IdeInputs.requireProtocolPath(view.getDirectory(), "directory");
      String workspacePath =
          IdeInputs.requireProtocolPath(view.getWorkspacePath(), "workspacePath");
      return publishOpen(
          sentIdentity,
          new IdeViewSnapshotStore.View(
              viewId,
              browserSessionId,
              eventNodeId,
              connectionId,
              claimId,
              bindingEpoch,
              confirmedDirectory,
              workspacePath,
              IdeProtocol.protocolState(IdeProtocol.requireViewState(view.getState()))));
    } catch (IdeCommandCaller.IdeOutcomeUnknownException error) {
      throw IdeProtocol.outcomeUnknown(unknown.getMessage(), unknown);
    } catch (DomainException error) {
      // readView 只有在当前 READY 实例已不等于发送实例时才用 VIEW_GONE 表示真实换代；
      // 同一实例上的 intent 消失仍是命令结果未知，不能倒因为“未发送”。
      if ("VIEW_GONE".equals(error.reason())) {
        Optional<DaemonConnectionIdentity> currentAfter =
            transport.connectionIdentity(environmentId);
        if (currentAfter.isPresent() && !currentAfter.get().equals(sentIdentity)) {
          throw error;
        }
      }
      if ("VIEW_GONE".equals(error.reason()) || "IDE_UNAVAILABLE".equals(error.reason())) {
        unknown.addSuppressed(error);
        throw IdeProtocol.outcomeUnknown(unknown.getMessage(), unknown);
      }
      throw error;
    } catch (RuntimeException error) {
      unknown.addSuppressed(error);
      throw IdeProtocol.outcomeUnknown(unknown.getMessage(), unknown);
    }
  }

  /** 落实一次性入口票据；授权事实已经完全来自刚接受的权威快照。 */
  private EnvironmentIdeViewDTO publishOpen(
      DaemonConnectionIdentity identity, IdeViewSnapshotStore.View view) {
    snapshotBoundary.requireCurrentGeneration(identity);
    String ticket =
        bootstrapTickets.issue(
            identity, view.viewId().toString(), view.bindingEpoch(), view.workspacePath());
    EnvironmentIdeViewDTO dto = IdeProtocol.toDto(identity, view);
    dto.setEntryUrl(
        IdeGatewayConstants.ROUTE_PREFIX
            + identity.environmentId()
            + "/"
            + view.viewId()
            + IdeGatewayConstants.BOOTSTRAP_ENDPOINT
            + "?token="
            + ticket);
    return dto;
  }

  @Override
  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = IdeInputs.requireViewId(viewId);
    UUID browserSessionId =
        IdeUuids.requireInput(request.getBrowserSessionId(), "browserSessionId");
    long bindingEpoch = IdeInputs.requireEpoch(request.getBindingEpoch());
    String directory = IdeInputs.requireDirectory(request.getDirectory());
    if (directory == null) {
      throw DomainException.validation("environment", "directory must not be blank");
    }
    Binding binding =
        bindings.requireBinding(
            environmentId,
            parsedViewId,
            browserSessionId,
            bindingEpoch,
            request.getExpectedDaemonInstanceId());
    IdeCommand command =
        IdeCommand.newBuilder()
            .setAction(IdeAction.CHANGE_DIRECTORY)
            .setViewId(parsedViewId.toString())
            .setBrowserSessionId(browserSessionId.toString())
            .setBindingEpoch(bindingEpoch)
            .setExpectedDaemonInstanceId(binding.identity().daemonInstanceId())
            .setDirectory(directory)
            .build();
    IdeCall result = caller.callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      snapshotBoundary.acceptFailureSnapshot(result);
      throw IdeProtocol.rejection(result.reply());
    }
    IdeViewSnapshotStore.Snapshot snapshot =
        snapshotBoundary.requireReplySnapshotForBinding(result, binding.view());
    IdeViewSnapshotStore.View current =
        requireCurrentView(snapshot, binding.view(), parsedViewId, browserSessionId);
    return IdeProtocol.toDto(binding.identity(), current);
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
    UUID parsedViewId = IdeInputs.requireViewId(viewId);
    UUID browserSessionId =
        IdeUuids.requireInput(request.getBrowserSessionId(), "browserSessionId");
    long bindingEpoch = IdeInputs.requireEpoch(request.getBindingEpoch());
    Binding binding =
        bindings.requireBinding(
            environmentId,
            parsedViewId,
            browserSessionId,
            bindingEpoch,
            request.getExpectedDaemonInstanceId());
    IdeCall result =
        caller.callConfirmed(
            environmentId,
            bindingCommand(action, parsedViewId, browserSessionId, bindingEpoch, binding));
    if (!result.reply().getOk()) {
      snapshotBoundary.acceptFailureSnapshot(result);
      throw IdeProtocol.rejection(result.reply());
    }
    snapshotBoundary.acceptOptionalSnapshot(result, binding.view());
  }

  @Override
  public void closeView(
      EnvironmentId environmentId, String viewId, EnvironmentIdeCloseViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = IdeInputs.requireViewId(viewId);
    UUID browserSessionId =
        IdeUuids.requireInput(request.getBrowserSessionId(), "browserSessionId");
    long bindingEpoch = IdeInputs.requireEpoch(request.getBindingEpoch());
    boolean discard = request.isDiscard();
    Binding binding =
        bindings.requireBinding(
            environmentId,
            parsedViewId,
            browserSessionId,
            bindingEpoch,
            request.getExpectedDaemonInstanceId());
    IdeCommand command =
        IdeCommand.newBuilder()
            .setAction(IdeAction.CLOSE_VIEW)
            .setViewId(parsedViewId.toString())
            .setBrowserSessionId(browserSessionId.toString())
            .setBindingEpoch(bindingEpoch)
            .setExpectedDaemonInstanceId(binding.identity().daemonInstanceId())
            .setDiscard(discard)
            .build();
    IdeCall result = caller.callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      snapshotBoundary.acceptFailureSnapshot(result);
      if (IdeProtocol.isViewGone(result.reply())) {
        revokeExactBinding(binding.identity(), parsedViewId, bindingEpoch);
      }
      throw IdeProtocol.rejection(result.reply());
    }
    IdeViewSnapshotStore.Snapshot snapshot =
        snapshotBoundary.requireReplySnapshotForBinding(result, binding.view());
    IdeViewSnapshotStore.View current = snapshot.find(parsedViewId).orElse(null);
    if (current != null) {
      if (!current.browserSessionId().equals(browserSessionId)) {
        throw IdeProtocol.viewOwnedByOther();
      }
      if (!IdeSnapshots.sameBinding(current, binding.view())) {
        throw IdeProtocol.staleBinding("IDE close result belongs to an older binding");
      }
      if (current.state() == IdeViewState.OPEN) {
        throw IdeProtocol.protocolFailure(
            "daemon reported close success while the view remains open");
      }
    }
    revokeExactBinding(binding.identity(), parsedViewId, bindingEpoch);
  }

  @Override
  public EnvironmentIdeDirectoryListingDTO listDirectories(
      EnvironmentId environmentId, String path) {
    Objects.requireNonNull(environmentId, "environmentId");
    String directory = IdeInputs.requireDirectory(path);
    IdeCommand.Builder command = IdeCommand.newBuilder().setAction(IdeAction.LIST_DIRECTORIES);
    if (directory != null) {
      command.setDirectory(directory);
    }
    IdeResult reply = caller.callConfirmed(environmentId, command.build()).reply();
    if (!reply.getOk()) {
      throw IdeProtocol.rejection(reply);
    }
    return IdeProtocol.toDirectoryListing(reply);
  }

  /** owner 的当前视图快照；不创建视图，也不续租。只供本节点 lease 续租读取，不经跨节点转发。 */
  public EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    DaemonConnectionIdentity identity = bindings.requireReady(environmentId);
    IdeViewSnapshotStore.Snapshot snapshot = snapshotBoundary.refresh(environmentId, identity);
    return IdeProtocol.toViews(identity, snapshot);
  }

  @Override
  public EnvironmentIdeViewSnapshotDTO readView(
      EnvironmentId environmentId, String viewId, EnvironmentIdeReadViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = IdeInputs.requireViewId(viewId);
    UUID browserSessionId =
        IdeUuids.requireInput(request.getBrowserSessionId(), "browserSessionId");
    UUID eventNodeId = IdeUuids.requireInput(request.getEventNodeId(), "eventNodeId");
    UUID connectionId = IdeUuids.requireInput(request.getConnectionId(), "connectionId");
    UUID claimId = IdeUuids.requireInput(request.getClaimId(), "claimId");
    String expectedDaemonInstanceId =
        IdeInputs.optionalInstance(request.getExpectedDaemonInstanceId());

    DaemonConnectionIdentity identity = bindings.requireReady(environmentId);
    if (expectedDaemonInstanceId != null
        && !expectedDaemonInstanceId.equals(identity.daemonInstanceId())) {
      throw IdeProtocol.viewGone("environment daemon instance has been replaced");
    }
    bindings.requireIntent(
        eventNodeId, connectionId, browserSessionId, environmentId, parsedViewId, claimId);
    IdeViewSnapshotStore.Snapshot snapshot = snapshotBoundary.refresh(environmentId, identity);
    IdeViewSnapshotStore.View view = snapshot.find(parsedViewId).orElse(null);
    if (view != null && !view.browserSessionId().equals(browserSessionId)) {
      throw IdeProtocol.viewOwnedByOther();
    }
    EnvironmentIdeViewSnapshotDTO dto = new EnvironmentIdeViewSnapshotDTO();
    dto.setDaemonInstanceId(identity.daemonInstanceId());
    dto.setRevision(snapshot.revision());
    dto.setView(view == null ? null : IdeProtocol.toDto(identity, view));
    return dto;
  }

  private static IdeCommand bindingCommand(
      IdeAction action, UUID viewId, UUID browserSessionId, long bindingEpoch, Binding binding) {
    return IdeCommand.newBuilder()
        .setAction(action)
        .setViewId(viewId.toString())
        .setBrowserSessionId(browserSessionId.toString())
        .setBindingEpoch(bindingEpoch)
        .setExpectedDaemonInstanceId(binding.identity().daemonInstanceId())
        .build();
  }

  private IdeViewSnapshotStore.View requireOpenedView(
      IdeViewSnapshotStore.Snapshot snapshot,
      UUID viewId,
      UUID browserSessionId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId) {
    IdeViewSnapshotStore.View view =
        snapshot
            .find(viewId)
            .orElseThrow(
                () ->
                    IdeProtocol.protocolFailure(
                        "daemon opened view is missing from its IDE snapshot"));
    if (!view.browserSessionId().equals(browserSessionId)) {
      throw IdeProtocol.viewOwnedByOther();
    }
    if (!view.eventNodeId().equals(eventNodeId)
        || !view.connectionId().equals(connectionId)
        || !view.claimId().equals(claimId)) {
      throw IdeProtocol.staleBinding("daemon opened a different IDE binding for this claim");
    }
    return view;
  }

  private IdeViewSnapshotStore.View requireCurrentView(
      IdeViewSnapshotStore.Snapshot snapshot,
      IdeViewSnapshotStore.View expected,
      UUID viewId,
      UUID browserSessionId) {
    IdeViewSnapshotStore.View view =
        snapshot
            .find(viewId)
            .orElseThrow(
                () -> IdeProtocol.viewGone("IDE view is gone; open a new view before retrying"));
    if (!view.browserSessionId().equals(browserSessionId)) {
      throw IdeProtocol.viewOwnedByOther();
    }
    if (!IdeSnapshots.sameBinding(view, expected)) {
      throw IdeProtocol.staleBinding("IDE view binding has been replaced");
    }
    return view;
  }

  private void revokeExactBinding(
      DaemonConnectionIdentity identity, UUID viewId, long bindingEpoch) {
    bootstrapTickets.revoke(identity, viewId.toString(), bindingEpoch);
  }
}
