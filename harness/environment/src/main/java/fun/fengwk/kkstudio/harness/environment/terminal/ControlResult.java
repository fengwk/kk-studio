package fun.fengwk.kkstudio.harness.environment.terminal;

/**
 * 一次 writer 控制请求（CLAIM/TAKEOVER/recovery/RENEW/RELEASE）的结果。
 *
 * <p>{@link Status#GRANTED} 携带新授权的 epoch 与 token，以及跨连接恢复时核对出的旧操作决议 {@code recovered}；{@link
 * Status#RENEWED}/{@link Status#RELEASED} 表示续租/释放成功；{@link Status#BUSY} 表示存在未决操作、保守地保留现有围栏；{@link
 * Status#REJECTED} 给出具体拒绝原因。{@link #toString()} 不回显 token。
 *
 * <p>构造时校验字段与状态一致：只有 {@link Status#GRANTED} 携带 grant，只有 {@link Status#REJECTED} 携带 reason，{@code
 * recovered} 只可能出现在 GRANTED。工厂方法供 reducer 与 wire 编解码共用同一套结果构造。
 *
 * @param status 结果类别
 * @param grant 授权成功时的 grant（含 token secret），否则为 {@code null}
 * @param recovered 跨连接恢复核对出的旧操作决议，否则为 {@code null}
 * @param reason {@link Status#REJECTED} 时的拒绝原因，否则为 {@code null}
 */
public record ControlResult(
    Status status, WriterGrant grant, OperationOutcome recovered, RejectReason reason) {

  /** 控制请求结果类别。 */
  public enum Status {
    GRANTED,
    RENEWED,
    RELEASED,
    BUSY,
    REJECTED
  }

  /** 控制请求拒绝原因。 */
  public enum RejectReason {
    /** owner/epoch/token 不匹配。 */
    NOT_OWNER,
    /** 控制权已因租期失效被围住，不能再续租或释放。 */
    LEASE_EXPIRED,
    /** writer 被冻结，不再授权或重放。 */
    FROZEN,
    /** takeover 观察到的 expectedWriterEpoch 与当前不匹配。 */
    CAS_FAILED,
    /** 恢复请求参数非法（seq 越界，或 seq 与摘要的存在性不一致）。 */
    INVALID,
    /** 跨连接恢复提供的旧 epoch/token 不是当前控制者。 */
    RECOVERY_MISMATCH,
    /** 跨连接恢复携带的旧操作无法核对，结果不确定。 */
    RECOVERY_UNVERIFIABLE,
    /** 同一 requestId 复用于不同 owner/CAS/参数。 */
    REQUEST_CONFLICT
  }

  public ControlResult {
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    if ((status == Status.GRANTED) != (grant != null)) {
      throw new IllegalArgumentException("only GRANTED carries a grant");
    }
    if ((status == Status.REJECTED) != (reason != null)) {
      throw new IllegalArgumentException("only REJECTED carries a reason");
    }
    if (recovered != null && status != Status.GRANTED) {
      throw new IllegalArgumentException("recovered outcome is only valid on GRANTED");
    }
  }

  public static ControlResult granted(WriterGrant grant, OperationOutcome recovered) {
    return new ControlResult(Status.GRANTED, grant, recovered, null);
  }

  public static ControlResult renewed() {
    return new ControlResult(Status.RENEWED, null, null, null);
  }

  public static ControlResult released() {
    return new ControlResult(Status.RELEASED, null, null, null);
  }

  public static ControlResult busy() {
    return new ControlResult(Status.BUSY, null, null, null);
  }

  public static ControlResult rejected(RejectReason reason) {
    return new ControlResult(Status.REJECTED, null, null, reason);
  }

  /** 是否授予了新的（或既有的）控制权。 */
  public boolean isGranted() {
    return status == Status.GRANTED;
  }
}
