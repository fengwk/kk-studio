package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 writer 授权的两个标识：公开的 {@code epoch} 与私有的 {@code token} secret。
 *
 * <p>{@code epoch} 进入公开状态并作为 takeover 的 CAS 期望值；{@code token} 只在经授权的 grant/recovery
 * 响应里返回给控制者，续租、释放与跨连接恢复都必须精确匹配。{@link #toString()} 不回显 token，避免 secret 进入日志。
 */
public final class WriterGrant {

  private final UUID epoch;
  private final UUID token;

  public WriterGrant(UUID epoch, UUID token) {
    this.epoch = Objects.requireNonNull(epoch, "epoch");
    this.token = Objects.requireNonNull(token, "token");
  }

  /** 公开的控制权代际；可进入公开状态与 CAS。 */
  public UUID epoch() {
    return epoch;
  }

  /** 私有 secret；只返回给经授权的控制者，绝不进入公开状态。 */
  public UUID token() {
    return token;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof WriterGrant that
        && epoch.equals(that.epoch)
        && token.equals(that.token);
  }

  @Override
  public int hashCode() {
    return Objects.hash(epoch, token);
  }

  @Override
  public String toString() {
    return "WriterGrant[epoch=" + epoch + ", token=<redacted>]";
  }
}
