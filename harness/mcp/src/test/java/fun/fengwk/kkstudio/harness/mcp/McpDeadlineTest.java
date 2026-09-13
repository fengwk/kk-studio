package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/** MCP 操作总预算 deadline 的语义测试。 */
class McpDeadlineTest {

  /** 验证正常时间范围内的剩余计算及超时检测：超时后剩余为零且 requireRemaining 抛超时。 */
  @Test
  void calculatesRemainingAndDetectsTimeout() throws InterruptedException {
    McpDeadline deadline = McpDeadline.of(Duration.ofMillis(300));
    assertThat(deadline.isExpired()).isFalse();
    assertThat(deadline.remaining().toMillis()).isGreaterThan(0);

    Thread.sleep(350);
    assertThat(deadline.isExpired()).isTrue();
    assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    assertThatThrownBy(deadline::requireRemaining)
        .isInstanceOf(McpTimeoutException.class)
        .hasMessageContaining("deadline exceeded");
  }

  /** 验证没有「无限预算」便利形式：库只接受显式正时长或绝对截止时间，避免生产路径出现无法限时的调用。 */
  @Test
  void alwaysRequiresExplicitPositiveBudget() {
    assertThatThrownBy(() -> McpDeadline.of(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpDeadline.of(Duration.ofMillis(-10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpDeadline.of(null)).isInstanceOf(NullPointerException.class);

    // 绝对截止时间形式同样可用，并且保持总预算语义
    McpDeadline absolute = McpDeadline.ofDeadline(Instant.now().plusSeconds(30));
    assertThat(absolute.isExpired()).isFalse();
    assertThat(absolute.remaining()).isPositive();
  }

  /** 验证「永不取消」令牌只解决取消需求，不替代 deadline：两者是彼此独立的显式参数。 */
  @Test
  void noneTokenIsNotAnUnlimitedBudget() {
    McpCancellationToken none = McpCancellationToken.none();
    assertThat(none.isCancelled()).isFalse();
    assertThat(McpCancellationToken.none()).isSameAs(none);
    // 即使使用 none 令牌，过期 deadline 仍必须失败
    McpDeadline expired = McpDeadline.ofDeadline(Instant.now().minusSeconds(1));
    assertThat(expired.isExpired()).isTrue();
    assertThatThrownBy(expired::requireRemaining).isInstanceOf(McpTimeoutException.class);
  }

  /** 绝对时间形式必须保持同一套总预算语义：未来截止未过期、剩余为正且有上界，{@link McpDeadline#deadline()} 回读原始时刻。 */
  @Test
  void absoluteDeadlineExposesRemainingAndExpiry() {
    Instant future = Instant.now().plusSeconds(120);
    McpDeadline deadline = McpDeadline.ofDeadline(future);
    assertThat(deadline.deadline()).isEqualTo(future);
    assertThat(deadline.isExpired()).isFalse();
    assertThat(deadline.remaining()).isPositive();
    assertThat(deadline.remaining()).isLessThanOrEqualTo(Duration.ofSeconds(120));
    deadline.checkNotExpired();

    // 恰好落在过去的时间点必须立即判定为过期，且剩余为零
    McpDeadline past = McpDeadline.ofDeadline(Instant.now().minusMillis(1));
    assertThat(past.isExpired()).isTrue();
    assertThat(past.remaining()).isEqualTo(Duration.ZERO);
    assertThatThrownBy(past::checkNotExpired).isInstanceOf(McpTimeoutException.class);
  }
}
