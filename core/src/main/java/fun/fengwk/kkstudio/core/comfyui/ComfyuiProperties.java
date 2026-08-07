package fun.fengwk.kkstudio.core.comfyui;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * ComfyUI runtime 配置。
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.comfyui")
@Data
public class ComfyuiProperties {

  /**
   * 是否启用 ComfyUI 集成：false 时不装配 {@code ComfyUIClient} bean，运行时调用（run / getJob / cancel /
   * download）确定性拒绝。
   */
  private boolean enabled;

  /** ComfyUI 服务地址（HTTP/WS 根地址），启用时必须非空白。 */
  private String baseUrl;

  /** 可选 API Key，敏感凭据：透传给 ComfyUIClient 用于服务鉴权，禁止写入日志。 */
  private String apiKey;

  /** 连接超时，默认 10 秒。 */
  private Duration connectTimeout = Duration.ofSeconds(10);

  /** 读超时，默认 30 秒；运行时阻塞等待上限为 readTimeout + 1 秒，必须为正值。 */
  private Duration readTimeout = Duration.ofSeconds(30);

  /** WebSocket 超时，默认 30 分钟。 */
  private Duration websocketTimeout = Duration.ofMinutes(30);

  /** 单文件输入大小上限，默认 50 MiB：S3 文件输入下载时按该上限做防御性校验，配置为负值时运行时拒绝。 */
  private DataSize maxInputFileSize = DataSize.ofMegabytes(50);
}
