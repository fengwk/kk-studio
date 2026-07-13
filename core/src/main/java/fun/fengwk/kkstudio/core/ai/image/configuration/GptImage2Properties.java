package fun.fengwk.kkstudio.core.ai.image.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-circle.ai.image.gpt-image-2")
@Data
public class GptImage2Properties {

  private String url;
  private String apiKey;
  private long timeoutMs = 3 * 60 * 1000L;
}
