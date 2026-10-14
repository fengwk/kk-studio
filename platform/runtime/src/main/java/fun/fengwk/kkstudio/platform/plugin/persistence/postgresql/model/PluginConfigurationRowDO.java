package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

/** {@code plugin_configuration_revision} 单行的读取投影。 */
@Data
public class PluginConfigurationRowDO {

  private String pluginId;

  private Long revision;

  private String codeVersion;

  private String schemaFingerprint;

  private String capabilityFingerprint;

  private String valuesJson;
}
