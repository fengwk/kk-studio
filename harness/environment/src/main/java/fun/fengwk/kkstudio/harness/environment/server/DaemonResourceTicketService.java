package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;

import java.util.Objects;
import java.util.UUID;

/**
 * 服务端资源上传票据服务的窄端口：把 Daemon 的 invocation 作用域上传请求映射到全局 Blob 上传契约。
 *
 * <p>核心只负责协议消息的严格校验、调用作用域绑定与票据重放；本接口承担数据库/对象存储 I/O，且绝不能在核心的状态锁内执行。实现是
 * 无状态的：传输绑定事实由核心持有，权威内容事实始终是全局上传行。
 *
 * <p>所有方法都必须对同一 transfer 的重复调用保持幂等：同进程重连后 Daemon 可以重发完全相同的申请与提交。
 */
public interface DaemonResourceTicketService {

  /**
   * 为一次上传申请票据。
   *
   * <p>内容已命中 ACTIVE blob 时直接返回 {@link Ticket.Ready}，Daemon 无需上传任何字节；否则返回 {@link Ticket.Pending}
   * 与预签名 PUT。
   *
   * @param environmentId 目标环境（传输必须绑定到该环境）
   * @param invocationId 调用 id（传输必须绑定到该在途调用）
   * @param request 已通过协议校验的申请
   * @return 本次传输的票据状态
   */
  Ticket reserve(EnvironmentId environmentId, String invocationId, TransferRequest request);

  /**
   * 提交一次已完成直传的上传并请求权威状态。
   *
   * <p>对象尚未就绪或校验失败时返回 {@link Ticket.Failed}，不抛异常：Daemon 依票据状态决定重试或放弃。
   *
   * @param environmentId 目标环境
   * @param invocationId 调用 id
   * @param uploadId Daemon 直传时使用的全局上传 id
   */
  Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId);

  /**
   * 幂等释放一次传输：未消费的上传按全局上传契约请求清理。
   *
   * <p>调用点包括调用终态、实例接管与传输放弃；实现必须容忍未知、未申请或已被消费的上传。
   *
   * @param environmentId 目标环境
   * @param invocationId 调用 id
   * @param uploadId 该传输已确定的全局上传 id；为 {@code null} 表示尚未申请到上传
   */
  void release(EnvironmentId environmentId, String invocationId, UUID uploadId);

  /** 协议校验通过的一次上传申请。 */
  record TransferRequest(UUID transferId, String mediaType, String name, long size, String sha256) {

    public TransferRequest {
      Objects.requireNonNull(transferId, "transferId");
      if (mediaType == null || mediaType.isBlank()) {
        throw new IllegalArgumentException("mediaType must not be blank");
      }
      if (size < 0) {
        throw new IllegalArgumentException("size must not be negative");
      }
    }
  }

  /** 服务端票据状态；与 wire 上的 {@code RESOURCE_UPLOAD_TICKET} 一一对应。 */
  sealed interface Ticket permits Ticket.Pending, Ticket.Ready, Ticket.Failed {

    /** uploadId 已确定但内容尚未就绪：Daemon 必须按预签名 PUT 直传后再提交。 */
    record Pending(UUID uploadId, DaemonPresignedPut presignedPut) implements Ticket {

      public Pending {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(presignedPut, "presignedPut");
      }
    }

    /** 内容已就绪（去重命中或提交完成）。 */
    record Ready(UUID uploadId) implements Ticket {

      public Ready {
        Objects.requireNonNull(uploadId, "uploadId");
      }
    }

    /** 本次票据动作失败：Daemon 可在 invocation deadline 内以同一 transfer 重试。 */
    record Failed(String message) implements Ticket {

      public Failed {
        if (message == null || message.isBlank()) {
          throw new IllegalArgumentException("message must not be blank");
        }
      }
    }

    /** 该票据对应的全局上传 id；{@link Failed} 不再是任何上传的 owner 时可能为 {@code null}。 */
    default UUID uploadId() {
      return switch (this) {
        case Pending pending -> pending.uploadId();
        case Ready ready -> ready.uploadId();
        case Failed ignored -> null;
      };
    }
  }
}
