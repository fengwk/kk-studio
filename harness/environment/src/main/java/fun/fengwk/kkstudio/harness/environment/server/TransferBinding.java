package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolException;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次资源传输的进程内绑定：申请事实恒定；申请票据、提交票据与已消耗的 uploadId 在首次成功后固化。
 *
 * <p>字段由该绑定自身的监视器保护，因此同一 transfer 的票据 I/O 串行进行：重复申请/提交（含瞬时 FAILED 后的重试与同进程重连重发）永不产生第二个上传行。
 * 绑定只在进程内存中存在，权威内容事实始终是全局上传行。
 */
final class TransferBinding {

  final DaemonResourceTicketService.TransferRequest request;

  /** 本次传输当前拥有的全局上传 id；申请或提交成功后固化，释放后不再回执。 */
  UUID currentUploadId;

  /** 申请票据；只在非 FAILED 时缓存，FAILED 必须允许后续重试再次触达票据服务。 */
  DaemonResourceTicketService.Ticket reserveTicket;

  /** 提交票据；只在非 FAILED 时缓存。 */
  DaemonResourceTicketService.Ticket commitTicket;

  /** 已释放标记：置位后不再回执、不再重复释放。 */
  boolean released;

  TransferBinding(DaemonResourceTicketService.TransferRequest request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  synchronized DaemonResourceTicketService.Ticket reserveTicket() {
    return reserveTicket;
  }

  synchronized DaemonResourceTicketService.Ticket commitTicket() {
    return commitTicket;
  }

  /** 瞬时失败不缓存：同一 transfer 的下一次重试必须再次触达票据服务，而不是重放失败结论。 */
  synchronized void recordFailure(boolean commit) {
    if (commit) {
      this.commitTicket = null;
    } else {
      this.reserveTicket = null;
    }
  }

  /**
   * 核对终态结果对该上传的引用与申请事实逐字段一致，且内容已就绪。
   *
   * <p>「已就绪」= 申请去重命中或提交已成功；PENDING/FAILED 说明内容根本不可消费，属于终态早于就绪的协议违规。
   */
  synchronized void requireReadyAndMatching(ResourceRef ref) {
    if (released) {
      throw new ControlProtocolException(
          "terminal result references an upload this invocation already released");
    }
    boolean ready =
        (reserveTicket instanceof DaemonResourceTicketService.Ticket.Ready)
            || (commitTicket instanceof DaemonResourceTicketService.Ticket.Ready);
    if (!ready) {
      throw new ControlProtocolException(
          "terminal result references an upload that is not ready: " + currentUploadId);
    }
    if (!Objects.equals(request.mediaType(), ref.mediaType())
        || !Objects.equals(request.name(), ref.name())
        || request.size() != ref.size()
        || !Objects.equals(request.sha256(), ref.sha256())) {
      throw new ControlProtocolException(
          "terminal result references an upload whose properties do not match the requested"
              + " transfer: "
              + currentUploadId);
    }
  }
}
