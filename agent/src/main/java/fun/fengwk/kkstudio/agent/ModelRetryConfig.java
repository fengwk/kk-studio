package fun.fengwk.kkstudio.agent;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * ModelRetryConfig 表示模型调用的全局重试配置。
 *
 * @author fengwk
 */
@Builder
@Data
public class ModelRetryConfig {

    /**
     * 最大重试次数。
     */
    private final int maxRetries;

    /**
     * 指数退避基础延迟。
     */
    private final Duration baseDelay;

    /**
     * 指数退避最大延迟。
     */
    private final Duration maxDelay;

    /**
     * 退避倍率。
     */
    private final double multiplier;

    public Duration nextDelay(int retryCount) {
        if (retryCount <= 0) {
            return Duration.ZERO;
        }
        long baseMillis = baseDelay == null ? 0L : baseDelay.toMillis();
        long maxMillis = maxDelay == null ? baseMillis : maxDelay.toMillis();
        if (baseMillis <= 0L) {
            return Duration.ZERO;
        }
        double factor = Math.pow(multiplier <= 0D ? 2D : multiplier, Math.max(0, retryCount - 1));
        long delayMillis = Math.min(maxMillis, Math.round(baseMillis * factor));
        return Duration.ofMillis(Math.max(0L, delayMillis));
    }

}
