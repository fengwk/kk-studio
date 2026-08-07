package fun.fengwk.kkstudio.core.ai.runtime.tool.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 部署级 Tool settings JSON。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.tool")
public class ToolSettingsProperties {

  /**
   * 部署级 Tool 设置 JSON（ToolSettingsCodec 规范形式：permission 规则 + defaultYolo），默认空对象 {@code "{}"}；非法 JSON
   * 在解码时拒绝。
   */
  private String settingsJson = "{}";
}
