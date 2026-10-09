package fun.fengwk.kkstudio.harness.daemon.terminal;

/**
 * 一次 INPUT/RESIZE 提交的准入决议。
 *
 * <p>{@link Kind#ACCEPTED} 是本 reducer 对某个 expected seq 唯一一次的正向决议，调用方只有拿到它才能调用 Runtime；{@link
 * Kind#PENDING} 表示相同在途操作仍在等待真实决议；{@link Kind#CONFIRMED} 表示该操作此前已决议，携带原结果，不再执行；{@link Kind#REJECTED}
 * 给出具体拒绝原因，且不消耗 seq。
 *
 * @param kind 决议类别
 * @param seq 本次操作的 seq
 * @param digest 本次操作的摘要，格式非法时为 {@code null}
 * @param outcome {@link Kind#CONFIRMED} 时该操作的既有结果，否则为 {@code null}
 * @param reason {@link Kind#REJECTED} 时的拒绝原因，否则为 {@code null}
 */
public record AdmissionResult(
    Kind kind, long seq, OperationDigest digest, OperationOutcome outcome, RejectReason reason) {

  /** 准入决议类别。 */
  public enum Kind {
    ACCEPTED,
    PENDING,
    CONFIRMED,
    REJECTED
  }

  /** 拒绝原因。 */
  public enum RejectReason {
    /** owner/epoch/token 不匹配。 */
    NOT_OWNER,
    /** 控制权已因租期失效被围住。 */
    LEASE_EXPIRED,
    /** writer 因结果不确定被冻结，不再接受授权或操作。 */
    FROZEN,
    /** 内容或 seq 不满足格式约束。 */
    INVALID,
    /** 与在途操作同 seq 但摘要不同。 */
    SEQ_CONFLICT,
    /** seq 超出当前应准入位置（未来缺口）。 */
    SEQ_GAP,
    /** 过旧 seq 的摘要已不再保留，无法核对。 */
    UNVERIFIABLE
  }

  static AdmissionResult accepted(long seq, OperationDigest digest) {
    return new AdmissionResult(Kind.ACCEPTED, seq, digest, null, null);
  }

  static AdmissionResult pending(long seq, OperationDigest digest) {
    return new AdmissionResult(Kind.PENDING, seq, digest, null, null);
  }

  static AdmissionResult confirmed(OperationOutcome outcome, long seq, OperationDigest digest) {
    return new AdmissionResult(Kind.CONFIRMED, seq, digest, outcome, null);
  }

  static AdmissionResult rejected(RejectReason reason, long seq, OperationDigest digest) {
    return new AdmissionResult(Kind.REJECTED, seq, digest, null, reason);
  }

  /** 是否为该 seq 的唯一正向准入。 */
  public boolean isAccepted() {
    return kind == Kind.ACCEPTED;
  }
}
