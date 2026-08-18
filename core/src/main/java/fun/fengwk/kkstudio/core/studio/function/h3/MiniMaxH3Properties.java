package fun.fengwk.kkstudio.core.studio.function.h3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** MiniMax-H3 Ref2VA adapter 的启动秘密；其余运行配置见 SystemSettings.integrations.minimaxH3。 */
@ConfigurationProperties(prefix = "kk-studio.canvas.function.minimax-h3")
@Data
public class MiniMaxH3Properties {

  private String comfyBearerToken;

  /** SystemSettings 判定 minimaxH3 启用时调用；comfyBearerToken 是唯一不在聚合内的必需秘密。 */
  public void requireBearerToken() {
    if (comfyBearerToken == null || comfyBearerToken.isBlank()) {
      throw new IllegalStateException(
          "kk-studio.canvas.function.minimax-h3.comfy-bearer-token is required when"
              + " integrations.minimaxH3 is enabled");
    }
  }
}
