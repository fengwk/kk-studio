package fun.fengwk.kkstudio.core.comfyui;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * ComfyUI runtime 配置。
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.comfyui")
@Data
public class ComfyuiProperties {

  private boolean enabled;
  private String baseUrl;
  private String apiKey;
  private Duration connectTimeout = Duration.ofSeconds(10);
  private Duration readTimeout = Duration.ofSeconds(30);
  private Duration websocketTimeout = Duration.ofMinutes(30);
  private DataSize maxInputFileSize = DataSize.ofMegabytes(50);
}
