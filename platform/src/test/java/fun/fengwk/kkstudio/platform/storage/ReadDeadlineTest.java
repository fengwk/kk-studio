package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * {@link ReadDeadline} 单元测试。
 *
 * <p>测试意图：读取预算一旦冻结就是绝对截止点 —— 剩余时间单调递减、到期后剩余为零且不可再作为请求预算。
 */
class ReadDeadlineTest {

  /** 测试意图：预算冻结后随真实时间递减，到期后 isExpired 为真、剩余时间夹紧为零。 */
  @Test
  void countsDownToExpiry() {
    ReadDeadline deadline = ReadDeadline.after(Duration.ofMillis(80L));

    assertFalse(deadline.isExpired());
    assertTrue(deadline.remaining().compareTo(Duration.ofMillis(80L)) <= 0);
    assertTrue(deadline.remaining().compareTo(Duration.ZERO) > 0);

    sleepQuietly(150L);

    assertTrue(deadline.isExpired());
    assertEquals(Duration.ZERO, deadline.remaining());
    assertTrue(deadline.remainingNanos() <= 0L);
  }

  /** 测试意图：非正预算不是合法读取预算，必须立即拒绝而不是产生“永不到期”的截止点。 */
  @Test
  void rejectsNonPositiveBudget() {
    assertThrows(IllegalArgumentException.class, () -> ReadDeadline.after(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> ReadDeadline.after(Duration.ofMillis(-1L)));
    assertThrows(NullPointerException.class, () -> ReadDeadline.after(null));
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
