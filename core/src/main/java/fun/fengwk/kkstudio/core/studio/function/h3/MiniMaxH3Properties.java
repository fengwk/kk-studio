package fun.fengwk.kkstudio.core.studio.function.h3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** MiniMax-H3 Ref2VA adapter 的显式运行配置。 */
@ConfigurationProperties(prefix = "kk-studio.canvas.function.minimax-h3")
@Data
public class MiniMaxH3Properties {

  private boolean enabled;
  private String promptAgentName;
  private String promptEnvironmentName;
  private long presignExpirySeconds = 600L;
  private Duration promptMaxWait = Duration.ofMinutes(10);
  private String comfyBaseUrl;
  private String comfyBearerToken;
  private Duration comfyConnectTimeout = Duration.ofSeconds(10);
  private Duration comfyRequestTimeout = Duration.ofSeconds(30);
  private Duration comfyPollInterval = Duration.ofSeconds(2);
  private Duration comfyMaxWait = Duration.ofMinutes(30);
}
