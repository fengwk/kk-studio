package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.time.Duration;

/**
 * Agent busy session stale 恢复配置。
 *
 * @author fengwk
 */
@RefreshScope
@ConfigurationProperties(prefix = "kk-studio.agent.runtime.busy-recovery")
@Data
public class AgentBusyRecoveryProperties {

    /** 是否启用 submit 触发的 stale busy 检查。 */
    private boolean enabled = true;

    /** busy session 超过多久没有事件写入才被视为 stale。 */
    private Duration busyTimeout = Duration.ofMinutes(30);

    /** stale 检查失败后最小重试延迟，避免 CAS 冲突时形成本地自旋。 */
    private Duration retryDelay = Duration.ofSeconds(5);

}
