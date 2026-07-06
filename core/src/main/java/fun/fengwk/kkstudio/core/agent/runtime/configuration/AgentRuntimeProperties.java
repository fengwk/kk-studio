package fun.fengwk.kkstudio.core.agent.runtime.configuration;

import fun.fengwk.kkstudio.agent.provider.ProviderType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.agent.provider.ProviderType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.agent.runtime")
@Data
public class AgentRuntimeProperties {

  private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

  @Data
  public static class ProviderProperties {

    private ProviderType providerType;
    private String baseUrl;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(60);
    private Duration streamIdleTimeout = Duration.ofSeconds(60);
  }
}
