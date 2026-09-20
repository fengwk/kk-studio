package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * MiniMax Mavis 凭据刷新调度算法（MiniMaxMavisRefreshSchedule）测试。
 *
 * <p>验证 min(取得+7d, 生命期中点) 调度公式的严格实现、极短 token 时退化为取得时刻、 以及过期时间早于或等于取得时刻时的防御性校验。
 */
class MiniMaxMavisRefreshScheduleTest {

  private static final Instant BASE = Instant.ofEpochSecond(1_700_000_000L);

  /** 当生命期中点早于 7 天时，刷新时刻应取生命期中点。 */
  @Test
  void schedulesMidpointWhenShorterThanSevenDays() {
    // 总生命期 6 天，中点为 3 天
    Instant expiresAt = BASE.plus(Duration.ofDays(6));
    Instant nextRefresh = MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, expiresAt);

    assertEquals(BASE.plus(Duration.ofDays(3)), nextRefresh);
  }

  /** 当生命期中点晚于 7 天时，刷新时刻应封顶为 7 天。 */
  @Test
  void capsIntervalAtSevenDaysWhenMidpointIsLonger() {
    // 总生命期 30 天，中点为 15 天，封顶为 7 天
    Instant expiresAt = BASE.plus(Duration.ofDays(30));
    Instant nextRefresh = MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, expiresAt);

    assertEquals(BASE.plus(Duration.ofDays(7)), nextRefresh);
  }

  /** 当生命期中点恰好为 7 天（总生命期 14 天）时，刷新时刻精确为 7 天。 */
  @Test
  void schedulesExactlySevenDaysWhenLifetimeIsFourteenDays() {
    Instant expiresAt = BASE.plus(Duration.ofDays(14));
    Instant nextRefresh = MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, expiresAt);

    assertEquals(BASE.plus(Duration.ofDays(7)), nextRefresh);
  }

  /** 极短 token（例如 1 纳秒）的生命期中点整除后为 0，退化为不早于取得时刻（精确等于取得时刻）。 */
  @Test
  void degeneratesToObtainedAtForExtremelyShortTokens() {
    Instant expiresAt = BASE.plusNanos(1);
    Instant nextRefresh = MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, expiresAt);

    assertEquals(BASE, nextRefresh, "Extremely short token must degenerate to obtainedAt");
  }

  /** 过期时间早于取得时间时必须抛出异常。 */
  @Test
  void rejectsExpiryBeforeObtained() {
    Instant expiredBefore = BASE.minusSeconds(10);
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, expiredBefore),
        "Expiry before obtainedAt must be rejected");
  }

  /** 过期时间等于取得时间时同样必须抛出异常。 */
  @Test
  void rejectsExpiryEqualToObtained() {
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, BASE),
        "Expiry equal to obtainedAt must be rejected");
  }

  /** 任意参数为 null 时抛出校验异常。 */
  @Test
  void rejectsNullInputs() {
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisRefreshSchedule.nextRefreshAt(null, BASE.plusSeconds(100)));
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisRefreshSchedule.nextRefreshAt(BASE, null));
  }
}
