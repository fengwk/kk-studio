package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

/** {@code plugin_catalog_revision} 单 epoch 不可变 manifest 行的读取投影。 */
@Data
public class PluginCatalogRevisionDO {

  private Long epoch;

  private String manifestJson;
}
