package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/** {@code plugin_configuration_revision} 单行的读取投影。 */
@Data
public class PluginConfigurationRowDO {

  private String pluginId;

  private Long revision;

  private String codeVersion;

  private String schemaFingerprint;

  private String capabilityFingerprint;

  private UUID authGeneration;

  private String contextFingerprint;

  private List<String> configuredSecretFields;

  private String valuesJson;
}
