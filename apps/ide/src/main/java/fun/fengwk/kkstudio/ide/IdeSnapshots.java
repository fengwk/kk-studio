package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 权威 IDE 快照的唯一读写入口：读 Daemon 当前完整快照，并把回执交给 {@link IdeViewSnapshotStore}，在换代、revision 与绑定漂移上失败关闭。
 *
 * <p>回执必须来自本节点当前 READY 实例；变更/关闭命令的回执还必须在写缓存前确认 owner 绑定未漂移，避免旧 claim/epoch 覆盖新快照。快照替换时按精确 epoch
 * 撤销被替换的 bootstrap 票据，并单向清理更早 epoch 的遗留票据。
 */
final class IdeSnapshots {

  private final IdeCommandCaller caller;
  private final IdeCommandTransport transport;
  private final IdeViewSnapshotStore store;
  private final IdeBootstrapTicketService bootstrapTickets;

  IdeSnapshots(
      IdeCommandCaller caller,
      IdeCommandTransport transport,
      IdeViewSnapshotStore store,
      IdeBootstrapTicketService bootstrapTickets) {
    this.caller = Objects.requireNonNull(caller, "caller");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.store = Objects.requireNonNull(store, "store");
    this.bootstrapTickets = Objects.requireNonNull(bootstrapTickets, "bootstrapTickets");
  }

  /** 已接受快照中该实例的视图；没有缓存时为空，不触发读取。 */
  Optional<IdeViewSnapshotStore.View> cachedView(DaemonConnectionIdentity identity, UUID viewId) {
    return store.snapshot(identity).flatMap(snapshot -> snapshot.find(viewId));
  }

  /** 读取 Daemon 当前完整快照并只接受合法 revision；纯读，不创建、不续租、不发票据。 */
  IdeViewSnapshotStore.Snapshot refresh(
      EnvironmentId environmentId, DaemonConnectionIdentity identity) {
    IdeCommand command = IdeCommand.newBuilder().setAction(IdeAction.LIST_VIEWS).build();
    IdeCall result = caller.callConfirmed(environmentId, command);
    if (!result.reply().getOk()) {
      acceptFailureSnapshot(result);
      throw IdeProtocol.rejection(result.reply());
    }
    if (!result.identity().equals(identity)) {
      throw IdeProtocol.staleBinding("IDE snapshot belongs to a replaced daemon instance");
    }
    return requireReplySnapshot(result);
  }

  /** 接受回执里的完整快照；没有 revision 视为协议错误。 */
  IdeViewSnapshotStore.Snapshot requireReplySnapshot(IdeCall result) {
    if (!result.reply().hasRevision()) {
      throw IdeProtocol.protocolFailure("daemon did not return a complete IDE snapshot");
    }
    return acceptSnapshot(result.identity(), result.reply());
  }

  /** 变更/关闭命令的普通成功回执只能确认原有 owner 绑定。 */
  IdeViewSnapshotStore.Snapshot requireReplySnapshotForBinding(
      IdeCall result, IdeViewSnapshotStore.View expected) {
    if (!result.reply().hasRevision()) {
      throw IdeProtocol.protocolFailure("daemon did not return a complete IDE snapshot");
    }
    requireReplyViewMatches(result.reply(), expected);
    return acceptSnapshot(result.identity(), result.reply());
  }

  /** 失败回执携带快照时同样接受；没有快照则什么都不写。 */
  void acceptFailureSnapshot(IdeCall result) {
    if (result.reply().hasRevision()) {
      acceptSnapshot(result.identity(), result.reply());
    }
  }

  /** 成功回执携带快照时先核对绑定再接受；没有快照表示 Daemon 未改变视图。 */
  void acceptOptionalSnapshot(IdeCall result, IdeViewSnapshotStore.View expected) {
    if (result.reply().hasRevision()) {
      requireReplyViewMatches(result.reply(), expected);
      acceptSnapshot(result.identity(), result.reply());
    }
  }

  private void requireReplyViewMatches(IdeResult reply, IdeViewSnapshotStore.View expected) {
    for (IdeViewSummary view : reply.getViewsList()) {
      if (expected.viewId().equals(IdeProtocol.protocolUuid(view.getViewId(), "viewId"))) {
        if (!sameBinding(IdeProtocol.toSnapshotView(view), expected)) {
          throw IdeProtocol.staleBinding("IDE command reply contains a different binding");
        }
        return;
      }
    }
  }

  /** 比较稳定的 owner 绑定字段；目录可在 CHANGE_DIRECTORY 中合法变化。 */
  static boolean sameBinding(IdeViewSnapshotStore.View actual, IdeViewSnapshotStore.View expected) {
    return actual.viewId().equals(expected.viewId())
        && actual.browserSessionId().equals(expected.browserSessionId())
        && actual.eventNodeId().equals(expected.eventNodeId())
        && actual.connectionId().equals(expected.connectionId())
        && actual.claimId().equals(expected.claimId())
        && actual.bindingEpoch() == expected.bindingEpoch();
  }

  private IdeViewSnapshotStore.Snapshot acceptSnapshot(
      DaemonConnectionIdentity identity, IdeResult reply) {
    requireCurrentGeneration(identity);
    IdeViewSnapshotStore.Acceptance acceptance =
        store.accept(identity, reply.getRevision(), IdeProtocol.toSnapshotViews(reply));
    if (acceptance.applied() && acceptance.previous() != null) {
      cleanupReplacedTickets(identity, acceptance.previous(), acceptance.snapshot());
    }
    return acceptance.snapshot();
  }

  /** 锁外快速复核：回执/快照必须来自本节点当前 READY 实例；快照存储锁内还会再复核一次。 */
  void requireCurrentGeneration(DaemonConnectionIdentity identity) {
    if (!transport
        .connectionIdentity(identity.environmentId())
        .map(identity::equals)
        .orElse(false)) {
      throw IdeProtocol.staleBinding("IDE result belongs to a replaced daemon instance");
    }
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
    // 只单向清理严格更早的遗留票据：并发晚执行的旧快照清理不得删除更新 epoch 已签发的票据。
    for (IdeViewSnapshotStore.View view : current.views()) {
      bootstrapTickets.revokeOlderThan(identity, view.viewId().toString(), view.bindingEpoch());
    }
  }
}
