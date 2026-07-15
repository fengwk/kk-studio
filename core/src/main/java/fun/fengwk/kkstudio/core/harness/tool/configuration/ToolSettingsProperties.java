package fun.fengwk.kkstudio.core.harness.tool.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-wide Tool settings JSON. */
@Data
@ConfigurationProperties(prefix = "kk-studio.tool")
public class ToolSettingsProperties {
  private String settingsJson = "{}";
}
