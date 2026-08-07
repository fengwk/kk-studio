package fun.fengwk.kkstudio.core.ai.image.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-circle.ai.image.gpt-image-2")
@Data
public class GptImage2Properties {

  /** GPT Image 2 服务 endpoint（HTTP POST JSON 请求）。 */
  private String url;

  /** API Key，敏感凭据：以 {@code Authorization: Bearer <apiKey>} 请求头发送，禁止写入日志。 */
  private String apiKey;

  /** 单次请求超时时间（毫秒），默认 3 分钟。 */
  private long timeoutMs = 3 * 60 * 1000L;
}
