package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.time.Duration;
import java.time.Instant;

/**
 * MiniMax Mavis 的下一次刷新时刻公式。
 *
 * <p>MiniMax 没有 OAuth {@code refresh_token}，刷新用当前 access token 换新 token，因此刷新必须发生在 token 仍有效的窗口内：默认取
 * {@code min(取得时刻 + 7d, token 生命期中点)}。生命期中点保证刷新请求至少还有一半剩余寿命可用，7 天上限避免超长寿命 token 被过早刷新。
 *
 * <p>公式结果不晚于「现在」时（异常短的 token）退化为「现在」，由调度周期决定实际重试节奏，不会形成忙循环。
 */
public final class MiniMaxMavisRefreshSchedule {

  /** 刷新间隔的固定上限。 */
  public static final Duration MAX_INTERVAL = Duration.ofDays(7);

  private MiniMaxMavisRefreshSchedule() {}

  /** 计算下一次刷新时刻；{@code expiresAt} 必须晚于 {@code obtainedAt}。 */
  public static Instant nextRefreshAt(Instant obtainedAt, Instant expiresAt) {
    if (obtainedAt == null || expiresAt == null) {
      throw new MavisValidationException("refresh schedule requires obtained and expiry times");
    }
    if (!expiresAt.isAfter(obtainedAt)) {
      throw new MavisValidationException("access token expiry must be after the obtained time");
    }
    Duration lifetime = Duration.between(obtainedAt, expiresAt);
    Instant midpoint = obtainedAt.plus(lifetime.dividedBy(2));
    Instant sevenDays = obtainedAt.plus(MAX_INTERVAL);
    Instant scheduled = midpoint.isBefore(sevenDays) ? midpoint : sevenDays;
    return scheduled.isAfter(obtainedAt) ? scheduled : obtainedAt;
  }
}
