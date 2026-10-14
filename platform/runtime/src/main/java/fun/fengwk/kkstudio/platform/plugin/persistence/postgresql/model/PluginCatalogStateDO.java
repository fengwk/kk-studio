package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

/** {@code plugin_catalog_state} 唯一 id=1 行的读取投影。 */
@Data
public class PluginCatalogStateDO {

  private Long id;

  private Long epoch;

  private String manifestJson;
}
