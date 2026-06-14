package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent busy recovery 配置。
 *
 * @author fengwk
 */
@EnableConfigurationProperties(AgentBusyRecoveryProperties.class)
@Configuration
public class AgentBusyRecoveryConfiguration {
}
