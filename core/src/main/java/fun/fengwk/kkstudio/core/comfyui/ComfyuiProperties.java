package fun.fengwk.kkstudio.core.comfyui;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ComfyUI 部署级密钥配置；运行参数（是否启用、baseUrl、超时、输入预算）由 SystemSettings.integrations.comfyui 提供。
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.comfyui")
@Data
public class ComfyuiProperties {

  /** 可选 API Key，敏感凭据：透传给 ComfyUIClient 用于服务鉴权，禁止写入日志。 */
  private String apiKey;
}
