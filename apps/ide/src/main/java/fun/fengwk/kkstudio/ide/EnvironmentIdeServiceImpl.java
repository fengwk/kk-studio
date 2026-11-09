package fun.fengwk.kkstudio.ide;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * IDE 视图控制服务实现：owner 在发任何命令前经事件节点核对 exact intent，然后通过既有 lease/READY 围栏下发一条 {@code IDE_COMMAND}。
 *
 * <p>所有可见状态只认 Daemon 返回的完整 {@code {revision, views}} 快照；Backend 只保留一个不可变高 revision 快照缓存。更高 revision
 * 原子替换，更低 revision 不生效，旧实例或旧 epoch 的回执、票据与授权清理都不能覆盖新绑定。绑定查询、容量、bootstrap 与数据面授权读取同一快照； 浏览器会话登记只保存
 * cookie。命令发送不确定时只返回 {@code IDE_OUTCOME_UNKNOWN}，仅 OPEN 按原 claim 纯读对账。
 */
@Slf4j
public class EnvironmentIdeServiceImpl implements EnvironmentIdeService {

  /** 除普通关闭外，单条 IDE 命令的默认等待上限。 */
  public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

  private final IdeCommandTransport transport;
  private final IdeBootstrapTicketService bootstrapTickets;
  private final IdeBrowserUseGateway browserUse;
  private final IdeViewSnapshotStore snapshots;
  private final Duration commandTimeout;

  public EnvironmentIdeServiceImpl(
      IdeCommandTransport transport,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserUseGateway browserUse,
      IdeViewSnapshotStore snapshots,
      Duration commandTimeout) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
    this.browserUse = Objects.requireNonNull(browserUse, "browserUse");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
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
    UUID browserSessionId = parseUuid(request.getBrowserSessionId(), "browserSessionId");
    UUID eventNodeId = parseUuid(request.getEventNodeId(), "eventNodeId");
    UUID connectionId = parseUuid(request.getConnectionId(), "connectionId");
    UUID claimId = parseUuid(request.getClaimId(), "claimId");
    EnvironmentIdeOpenMode requestMode = request.getMode();
    if (requestMode == null) {
      throw DomainException.validation("environment", "mode must be create or resume");
    }
    IdeOpenMode mode = protocolMode(requestMode);
    long expectedBindingEpoch = expectedOpenEpoch(requestMode, request.getExpectedBindingEpoch());
    String directory = parseDirectory(request.getDirectory());

    DaemonConnectionIdentity identity = requireReadyIdentity(environmentId);
    String expectedDaemonInstanceId =
        expectedOpenDaemonInstance(requestMode, request.getExpectedDaemonInstanceId(), identity);
    requireIntent(eventNodeId, connectionId, browserSessionId, environmentId, viewId, claimId);

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

    IdeCall result;
    try {
      result = call(environmentId, commandBuilder.build());
    } catch (IdeOutcomeUnknownException error) {
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
      acceptFailureSnapshot(result);
      throw rejection(result.reply());
    }
    IdeViewSnapshotStore.Snapshot snapshot = requireReplySnapshot(result);
    IdeViewSnapshotStore.View view =
        requireOpenedView(snapshot, viewId, browserSessionId, eventNodeId, connectionId, claimId);
    return publishOpen(identity, view);
  }

  /**
   * 原 claim 结果未知时通过公开的纯读路径对账：只有 view/browserSession/eventNode/connection/claim 全部一致才认领。
   *
   * <p>快照不存在 view 是有效 absent 事实，但不是命令未执行的证明，因此保持 OUTCOME_UNKNOWN；实例已换代或出现更新的绑定则返回相应结构化原因。
   */
  private EnvironmentIdeViewDTO reconcileOpen(
      EnvironmentId environmentId,
      UUID viewId,
      UUID browserSessionId,
      UUID eventNodeId,
      UUID connectionId,
      UUID claimId,
      DaemonConnectionIdentity sentIdentity,
      IdeOutcomeUnknownException unknown) {
    try {
      Optional<DaemonConnectionIdentity> current = transport.connectionIdentity(environmentId);
      if (current.isEmpty()) {
        throw unknown;
      }
      if (!current.get().equals(sentIdentity)) {
        throw viewGone("environment daemon instance has been replaced");
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
        throw viewOwnedByOther();
      }
      if (!eventNodeId.toString().equals(view.getEventNodeId())
          || !connectionId.toString().equals(view.getConnectionId())
          || !claimId.toString().equals(view.getClaimId())) {
        throw staleBinding("IDE binding changed before the unknown OPEN_VIEW outcome was read");
      }
      long bindingEpoch = requirePositiveEpoch(view.getBindingEpoch());
      String confirmedDirectory = requirePath(view.getDirectory(), "directory");
      String workspacePath = requirePath(view.getWorkspacePath(), "workspacePath");
      EnvironmentIdeViewState state = requireViewState(view.getState());
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
              protocolState(state)));
    } catch (IdeOutcomeUnknownException error) {
      throw outcomeUnknown(unknown.getMessage(), unknown);
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
        throw outcomeUnknown(unknown.getMessage(), unknown);
      }
      throw error;
    } catch (RuntimeException error) {
      unknown.addSuppressed(error);
      throw outcomeUnknown(unknown.getMessage(), unknown);
    }
  }

  /** 落实一次性入口票据；授权事实已经完全来自刚接受的权威快照。 */
  private EnvironmentIdeViewDTO publishOpen(
      DaemonConnectionIdentity identity, IdeViewSnapshotStore.View view) {
    requireCurrentGeneration(identity);
    String ticket =
        bootstrapTickets.issue(
            identity, view.viewId().toString(), view.bindingEpoch(), view.workspacePath());
    EnvironmentIdeViewDTO dto = toDto(identity, view);
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
    UUID parsedViewId = parseViewId(viewId);
    UUID browserSessionId = parseUuid(request.getBrowserSessionId(), "browserSessionId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    String directory = parseDirectory(request.getDirectory());
    if (directory == null) {
      throw DomainException.validation("environment", "directory must not be blank");
    }
    EnvironmentIdeViewBindingContext binding =
        requireBinding(
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
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      acceptFailureSnapshot(result);
      throw rejection(result.reply());
    }
    IdeViewSnapshotStore.Snapshot snapshot =
        requireReplySnapshotForBinding(result, binding.view(), parsedViewId);
    IdeViewSnapshotStore.View current =
        requireCurrentView(snapshot, binding.view(), parsedViewId, browserSessionId, bindingEpoch);
    return toDto(binding.identity(), current);
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
    UUID browserSessionId = parseUuid(request.getBrowserSessionId(), "browserSessionId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    EnvironmentIdeViewBindingContext binding =
        requireBinding(
            environmentId,
            parsedViewId,
            browserSessionId,
            bindingEpoch,
            request.getExpectedDaemonInstanceId());
    IdeCommand command =
        IdeCommand.newBuilder()
            .setAction(action)
            .setViewId(parsedViewId.toString())
            .setBrowserSessionId(browserSessionId.toString())
            .setBindingEpoch(bindingEpoch)
            .setExpectedDaemonInstanceId(binding.identity().daemonInstanceId())
            .build();
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      acceptFailureSnapshot(result);
      throw rejection(result.reply());
    }
    acceptOptionalSnapshot(result, binding.view(), parsedViewId);
  }

  @Override
  public void closeView(
      EnvironmentId environmentId, String viewId, EnvironmentIdeCloseViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID browserSessionId = parseUuid(request.getBrowserSessionId(), "browserSessionId");
    long bindingEpoch = parseEpoch(request.getBindingEpoch());
    boolean discard = request.isDiscard();
    EnvironmentIdeViewBindingContext binding =
        requireBinding(
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
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      acceptFailureSnapshot(result);
      if (isViewGone(result.reply())) {
        revokeExactBinding(binding.identity(), parsedViewId, bindingEpoch);
      }
      throw rejection(result.reply());
    }
    IdeViewSnapshotStore.Snapshot snapshot =
        requireReplySnapshotForBinding(result, binding.view(), parsedViewId);
    IdeViewSnapshotStore.View current = snapshot.find(parsedViewId).orElse(null);
    if (current != null) {
      if (!current.browserSessionId().equals(browserSessionId)) {
        throw viewOwnedByOther();
      }
      if (!sameBinding(current, binding.view())) {
        throw staleBinding("IDE close result belongs to an older binding");
      }
      if (current.state() == IdeViewState.OPEN) {
        throw protocolFailure("daemon reported close success while the view remains open");
      }
    }
    revokeExactBinding(binding.identity(), parsedViewId, bindingEpoch);
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
    DaemonConnectionIdentity identity = requireReadyIdentity(environmentId);
    IdeViewSnapshotStore.Snapshot snapshot = refreshSnapshot(environmentId, identity);
    EnvironmentIdeViewsDTO dto = new EnvironmentIdeViewsDTO();
    dto.setDaemonInstanceId(identity.daemonInstanceId());
    dto.setRevision(snapshot.revision());
    dto.setViews(snapshot.views().stream().map(view -> toDto(identity, view)).toList());
    return dto;
  }

  @Override
  public EnvironmentIdeViewSnapshotDTO readView(
      EnvironmentId environmentId, String viewId, EnvironmentIdeReadViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    UUID parsedViewId = parseViewId(viewId);
    UUID browserSessionId = parseUuid(request.getBrowserSessionId(), "browserSessionId");
    UUID eventNodeId = parseUuid(request.getEventNodeId(), "eventNodeId");
    UUID connectionId = parseUuid(request.getConnectionId(), "connectionId");
    UUID claimId = parseUuid(request.getClaimId(), "claimId");
    String expectedDaemonInstanceId = parseOptionalInstance(request.getExpectedDaemonInstanceId());

    DaemonConnectionIdentity identity = requireReadyIdentity(environmentId);
    if (expectedDaemonInstanceId != null
        && !expectedDaemonInstanceId.equals(identity.daemonInstanceId())) {
      throw viewGone("environment daemon instance has been replaced");
    }
    requireIntent(
        eventNodeId, connectionId, browserSessionId, environmentId, parsedViewId, claimId);
    IdeViewSnapshotStore.Snapshot snapshot = refreshSnapshot(environmentId, identity);
    IdeViewSnapshotStore.View view = snapshot.find(parsedViewId).orElse(null);
    if (view != null && !view.browserSessionId().equals(browserSessionId)) {
      throw viewOwnedByOther();
    }
    EnvironmentIdeViewSnapshotDTO dto = new EnvironmentIdeViewSnapshotDTO();
    dto.setDaemonInstanceId(identity.daemonInstanceId());
    dto.setRevision(snapshot.revision());
    dto.setView(view == null ? null : toDto(identity, view));
    return dto;
  }

  /** 读取 Daemon 当前完整快照并只接受合法 revision；纯读，不创建、不续租、不发票据。 */
  private IdeViewSnapshotStore.Snapshot refreshSnapshot(
      EnvironmentId environmentId, DaemonConnectionIdentity identity) {
    IdeCommand command = IdeCommand.newBuilder().setAction(IdeAction.LIST_VIEWS).build();
    IdeCall result = callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      acceptFailureSnapshot(result);
      throw rejection(result.reply());
    }
    if (!result.identity().equals(identity)) {
      throw staleBinding("IDE snapshot belongs to a replaced daemon instance");
    }
    return requireReplySnapshot(result);
  }

  private IdeViewSnapshotStore.Snapshot requireReplySnapshot(IdeCall result) {
    if (!result.reply().hasRevision()) {
      throw protocolFailure("daemon did not return a complete IDE snapshot");
    }
    return acceptSnapshot(result.identity(), result.reply());
  }

  /** 变更/关闭命令的普通成功回执只能确认原有 owner 绑定；绑定字段漂移的回执必须在写入缓存前拒绝， 不能让旧 claim/epoch 覆盖新快照。 */
  private IdeViewSnapshotStore.Snapshot requireReplySnapshotForBinding(
      IdeCall result, IdeViewSnapshotStore.View expected, UUID viewId) {
    if (!result.reply().hasRevision()) {
      throw protocolFailure("daemon did not return a complete IDE snapshot");
    }
    requireReplyViewMatches(result.reply(), expected, viewId);
    return acceptSnapshot(result.identity(), result.reply());
  }

  private void acceptFailureSnapshot(IdeCall result) {
    if (result.reply().hasRevision()) {
      acceptSnapshot(result.identity(), result.reply());
    }
  }

  private void acceptOptionalSnapshot(
      IdeCall result, IdeViewSnapshotStore.View expected, UUID viewId) {
    if (result.reply().hasRevision()) {
      requireReplyViewMatches(result.reply(), expected, viewId);
      acceptSnapshot(result.identity(), result.reply());
    }
  }

  private static void requireReplyViewMatches(
      IdeResult reply, IdeViewSnapshotStore.View expected, UUID viewId) {
    for (IdeViewSummary view : reply.getViewsList()) {
      if (expected.viewId().equals(protocolUuid(view.getViewId(), "viewId"))) {
        IdeViewSnapshotStore.View actual = toSnapshotView(view);
        if (!sameBinding(actual, expected)) {
          throw staleBinding("IDE command reply contains a different binding");
        }
        return;
      }
    }
  }

  private IdeViewSnapshotStore.Snapshot acceptSnapshot(
      DaemonConnectionIdentity identity, IdeResult reply) {
    requireCurrentGeneration(identity);
    List<IdeViewSnapshotStore.View> views =
        reply.getViewsList().stream().map(EnvironmentIdeServiceImpl::toSnapshotView).toList();
    IdeViewSnapshotStore.Acceptance acceptance =
        snapshots.accept(identity, reply.getRevision(), views);
    if (acceptance.applied() && acceptance.previous() != null) {
      cleanupReplacedTickets(identity, acceptance.previous(), acceptance.snapshot());
    }
    return acceptance.snapshot();
  }

  /** 旧快照中消失或换 epoch 的票据按精确 epoch 撤销；绝不按“其它 epoch”批量撤销。 */
  private void cleanupReplacedTickets(
      DaemonConnectionIdentity identity,
      IdeViewSnapshotStore.Snapshot previous,
      IdeViewSnapshotStore.Snapshot current) {
    for (IdeViewSnapshotStore.View old : previous.views()) {
      Optional<IdeViewSnapshotStore.View> replacement = current.find(old.viewId());
      if (replacement.isEmpty()
          || replacement.get().bindingEpoch() != old.bindingEpoch()
          || !replacement.get().workspacePath().equals(old.workspacePath())) {
        bootstrapTickets.revoke(identity, old.viewId().toString(), old.bindingEpoch());
      }
    }
    // 只单向清理严格更早的遗留票据；并发晚执行的 Rev2 清理不得删除已接受 Rev3 的票据。
    for (IdeViewSnapshotStore.View view : current.views()) {
      bootstrapTickets.revokeOlderThan(identity, view.viewId().toString(), view.bindingEpoch());
    }
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
                () -> protocolFailure("daemon opened view is missing from its IDE snapshot"));
    if (!view.browserSessionId().equals(browserSessionId)) {
      throw viewOwnedByOther();
    }
    if (!view.eventNodeId().equals(eventNodeId)
        || !view.connectionId().equals(connectionId)
        || !view.claimId().equals(claimId)) {
      throw staleBinding("daemon opened a different IDE binding for this claim");
    }
    return view;
  }

  private IdeViewSnapshotStore.View requireCurrentView(
      IdeViewSnapshotStore.Snapshot snapshot,
      IdeViewSnapshotStore.View expected,
      UUID viewId,
      UUID browserSessionId,
      long bindingEpoch) {
    IdeViewSnapshotStore.View view =
        snapshot
            .find(viewId)
            .orElseThrow(() -> viewGone("IDE view is gone; open a new view before retrying"));
    if (!view.browserSessionId().equals(browserSessionId)) {
      throw viewOwnedByOther();
    }
    if (!sameBinding(view, expected)) {
      throw staleBinding("IDE view binding has been replaced");
    }
    if (view.bindingEpoch() != bindingEpoch) {
      throw staleBinding("IDE view binding has been replaced");
    }
    return view;
  }

  /** 比较稳定的 owner 绑定字段；目录可在 CHANGE_DIRECTORY 中合法变化。 */
  private static boolean sameBinding(
      IdeViewSnapshotStore.View actual, IdeViewSnapshotStore.View expected) {
    return actual.viewId().equals(expected.viewId())
        && actual.browserSessionId().equals(expected.browserSessionId())
        && actual.eventNodeId().equals(expected.eventNodeId())
        && actual.connectionId().equals(expected.connectionId())
        && actual.claimId().equals(expected.claimId())
        && actual.bindingEpoch() == expected.bindingEpoch();
  }

  private EnvironmentIdeViewBindingContext requireBinding(
      EnvironmentId environmentId,
      UUID viewId,
      UUID browserSessionId,
      long bindingEpoch,
      String expectedDaemonInstanceId) {
    DaemonConnectionIdentity identity = requireReadyIdentity(environmentId);
    requireExpectedInstance(identity, expectedDaemonInstanceId);
    IdeViewSnapshotStore.Snapshot snapshot = snapshots.snapshot(identity).orElse(null);
    IdeViewSnapshotStore.View view = snapshot == null ? null : snapshot.find(viewId).orElse(null);
    if (view == null) {
      snapshot = refreshSnapshot(environmentId, identity);
      view = snapshot.find(viewId).orElse(null);
    }
    if (view == null) {
      throw viewGone("IDE view is gone; open a new view before retrying");
    }
    if (!view.browserSessionId().equals(browserSessionId)) {
      throw viewOwnedByOther();
    }
    if (view.bindingEpoch() != bindingEpoch) {
      throw staleBinding("IDE view binding has been replaced");
    }
    requireIntent(
        view.eventNodeId(),
        view.connectionId(),
        browserSessionId,
        environmentId,
        viewId,
        view.claimId());
    return new EnvironmentIdeViewBindingContext(identity, view);
  }

  private void requireIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID browserSessionId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    boolean matched;
    try {
      matched =
          browserUse.findExactIntent(
              eventNodeId, connectionId, browserSessionId, environmentId, viewId, claimId);
    } catch (DomainException error) {
      if ("IDE_UNAVAILABLE".equals(error.reason()) || "DAEMON_NOT_READY".equals(error.reason())) {
        throw error;
      }
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser IDE usage intent is unavailable", error);
    } catch (RuntimeException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser IDE usage intent is unavailable", error);
    }
    if (!matched) {
      throw viewGone("browser IDE usage intent is no longer confirmed");
    }
  }

  /** 本节点当前 READY 的 Daemon 进程身份；没有就绪连接时命令未发送。 */
  private DaemonConnectionIdentity requireReadyIdentity(EnvironmentId environmentId) {
    return transport
        .connectionIdentity(environmentId)
        .orElseThrow(
            () ->
                DomainException.unavailable("DAEMON_NOT_READY", "environment daemon is not ready"));
  }

  private void requireExpectedInstance(
      DaemonConnectionIdentity identity, String expectedDaemonInstanceId) {
    if (expectedDaemonInstanceId == null || expectedDaemonInstanceId.isBlank()) {
      throw DomainException.validation("environment", "expectedDaemonInstanceId is required");
    }
    if (!expectedDaemonInstanceId.equals(identity.daemonInstanceId())) {
      throw viewGone("environment daemon instance has been replaced");
    }
  }

  private static String expectedOpenDaemonInstance(
      EnvironmentIdeOpenMode mode,
      String expectedDaemonInstanceId,
      DaemonConnectionIdentity current) {
    if (expectedDaemonInstanceId == null || expectedDaemonInstanceId.isBlank()) {
      throw DomainException.validation(
          "environment", "expectedDaemonInstanceId is required for " + mode.wireValue());
    }
    if (!expectedDaemonInstanceId.equals(current.daemonInstanceId())) {
      throw viewGone("environment daemon instance has been replaced");
    }
    return expectedDaemonInstanceId;
  }

  private static long expectedOpenEpoch(EnvironmentIdeOpenMode mode, Long expectedBindingEpoch) {
    if (expectedBindingEpoch == null) {
      throw DomainException.validation("environment", "expectedBindingEpoch is required");
    }
    if (mode == EnvironmentIdeOpenMode.CREATE) {
      if (expectedBindingEpoch != 0) {
        throw DomainException.validation("environment", "create requires expectedBindingEpoch 0");
      }
      return 0;
    }
    if (expectedBindingEpoch <= 0) {
      throw DomainException.validation(
          "environment", "resume requires a positive expectedBindingEpoch");
    }
    return expectedBindingEpoch;
  }

  /** 锁外快速复核：回执/快照必须来自本节点当前 READY 实例；快照存储锁内还会再复核一次。 */
  private void requireCurrentGeneration(DaemonConnectionIdentity identity) {
    if (!transport
        .connectionIdentity(identity.environmentId())
        .map(identity::equals)
        .orElse(false)) {
      throw staleBinding("IDE result belongs to a replaced daemon instance");
    }
  }

  private IdeCall call(EnvironmentId environmentId, IdeCommand command) {
    IdeRequest request;
    try {
      request = transport.requestIde(environmentId, command);
    } catch (EnvironmentCapabilitySendUncertainException error) {
      throw new IdeOutcomeUnknownException("environment IDE command outcome is uncertain", error);
    } catch (EnvironmentCapabilityUnavailableException | EnvironmentCapabilityBusyException error) {
      throw DomainException.unavailable(
          "DAEMON_NOT_READY", "environment daemon is not ready", error);
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
      throw outcomeUnknown(error.getMessage(), error);
    }
  }

  private void revokeExactBinding(
      DaemonConnectionIdentity identity, UUID viewId, long bindingEpoch) {
    bootstrapTickets.revoke(identity, viewId.toString(), bindingEpoch);
  }

  private static IdeViewSnapshotStore.View toSnapshotView(IdeViewSummary view) {
    try {
      return new IdeViewSnapshotStore.View(
          protocolUuid(view.getViewId(), "viewId"),
          protocolUuid(view.getBrowserSessionId(), "browserSessionId"),
          protocolUuid(view.getEventNodeId(), "eventNodeId"),
          protocolUuid(view.getConnectionId(), "connectionId"),
          protocolUuid(view.getClaimId(), "claimId"),
          view.getBindingEpoch(),
          requirePath(view.getDirectory(), "directory"),
          requirePath(view.getWorkspacePath(), "workspacePath"),
          view.getState());
    } catch (DomainException error) {
      throw error;
    } catch (RuntimeException error) {
      throw DomainException.unavailable(
          "IDE_PROTOCOL_ERROR", "daemon returned an invalid IDE view snapshot", error);
    }
  }

  private static EnvironmentIdeViewDTO toDto(
      DaemonConnectionIdentity identity, IdeViewSnapshotStore.View view) {
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(view.viewId().toString());
    dto.setEnvironmentId(identity.environmentId().toString());
    dto.setBrowserSessionId(view.browserSessionId().toString());
    dto.setEventNodeId(view.eventNodeId().toString());
    dto.setConnectionId(view.connectionId().toString());
    dto.setClaimId(view.claimId().toString());
    dto.setDaemonInstanceId(identity.daemonInstanceId());
    dto.setBindingEpoch(view.bindingEpoch());
    dto.setDirectory(view.directory());
    dto.setWorkspacePath(view.workspacePath());
    dto.setState(EnvironmentIdeViewState.valueOf(view.state().name()));
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

  private static UUID protocolUuid(String value, String field) {
    try {
      return Uuids.parseCanonical(value, field);
    } catch (IllegalArgumentException error) {
      throw protocolFailure("daemon did not return a canonical " + field);
    }
  }

  private static long parseEpoch(Long value) {
    if (value == null || value <= 0) {
      throw DomainException.validation("environment", "bindingEpoch must be positive");
    }
    return value;
  }

  private static long requirePositiveEpoch(Long value) {
    if (value == null || value <= 0) {
      throw protocolFailure("daemon did not return a positive binding epoch");
    }
    return value;
  }

  private static String parseOptionalInstance(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw DomainException.validation("environment", "expectedDaemonInstanceId must not be blank");
    }
    return value;
  }

  private static EnvironmentIdeViewState requireViewState(EnvironmentIdeViewState state) {
    if (state == null) {
      throw protocolFailure("daemon did not return an IDE view state");
    }
    return state;
  }

  private static IdeViewState protocolState(EnvironmentIdeViewState state) {
    return switch (state) {
      case OPEN -> IdeViewState.OPEN;
      case CLOSING -> IdeViewState.CLOSING;
    };
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
    String code = reply.hasCode() ? reply.getCode() : "";
    return switch (code) {
      case "VIEW_GONE" -> viewGone(message);
      case "VIEW_OWNED_BY_OTHER_SESSION" -> viewOwnedByOther();
      case "STALE_BINDING" -> staleBinding(message);
      case "CLAIM_CONFLICT" -> DomainException.conflict("CLAIM_CONFLICT", message);
      case "CAPACITY_EXCEEDED", "IDE_CAPACITY_EXCEEDED", "VIEW_LIMIT_REACHED" -> DomainException
          .conflict("IDE_CAPACITY_EXCEEDED", message);
      case "CLEANUP_FAILED", "IDE_CLEANUP_FAILED" -> DomainException.unavailable(
          "IDE_CLEANUP_FAILED", message);
      case "CANCELLED" -> DomainException.validationReason("IDE_CLOSE_CANCELLED", message);
      case "DIRTY" -> DomainException.validationReason("DIRTY", message);
      case "DAEMON_NOT_READY" -> DomainException.unavailable("DAEMON_NOT_READY", message);
      case "OUTCOME_UNKNOWN", "IDE_OUTCOME_UNKNOWN" -> DomainException.unavailable(
          "IDE_OUTCOME_UNKNOWN", message);
      case "UNAVAILABLE", "BUSY", "VIEW_IN_USE" -> DomainException.unavailable(
          "IDE_UNAVAILABLE", message);
      default -> DomainException.validation("environment", message);
    };
  }

  private static DomainException viewGone(String message) {
    return DomainException.unavailable("VIEW_GONE", message);
  }

  private static DomainException viewOwnedByOther() {
    return DomainException.conflict(
        "VIEW_OWNED_BY_OTHER_SESSION", "IDE view is owned by another browser session");
  }

  private static DomainException staleBinding(String message) {
    return DomainException.conflict("STALE_BINDING", message);
  }

  private static DomainException outcomeUnknown(String message, Throwable cause) {
    return DomainException.unavailable("IDE_OUTCOME_UNKNOWN", message, cause);
  }

  private static DomainException protocolFailure(String message) {
    return DomainException.unavailable("IDE_PROTOCOL_ERROR", message);
  }

  private static IdeOpenMode protocolMode(EnvironmentIdeOpenMode mode) {
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

  private record EnvironmentIdeViewBindingContext(
      DaemonConnectionIdentity identity, IdeViewSnapshotStore.View view) {}

  /** 命令可能已到达 Daemon 但未收到确定回执；只允许 OPEN 按原 claim 纯读对账。 */
  private static final class IdeOutcomeUnknownException extends RuntimeException {

    private IdeOutcomeUnknownException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
