package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Objects;

/**
 * 跨连接恢复 CLAIM 携带的旧操作核对参数。
 *
 * <p>{@code previous} 是上一段连接的 epoch/token secret；{@code seq} 是待核对的旧操作序号，0 表示没有未决操作且 {@code digest}
 * 必须为 {@code null}，正数表示必须携带对应摘要。恢复是 CLAIM 的一个分支，没有独立的 RECOVER/RENEW RPC。
 *
 * @param previous 旧授权（含 token secret）
 * @param seq 待核对旧操作的 seq，无旧操作时为 0
 * @param digest 待核对旧操作的摘要，无旧操作时为 {@code null}
 */
public record Recovery(WriterGrant previous, long seq, OperationDigest digest) {

  public Recovery {
    Objects.requireNonNull(previous, "previous");
    if (seq < 0 || seq > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("seq out of range: " + seq);
    }
    if ((seq == 0) != (digest == null)) {
      throw new IllegalArgumentException("digest must be present exactly when seq is positive");
    }
  }
}
