package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

/**
 * {@code plugin_catalog_state} 唯一 id=1 指针行的读取投影：只携带 epoch，manifest 由 {@link
 * PluginCatalogRevisionDO} 承载。
 */
@Data
public class PluginCatalogStateDO {

  private Long id;

  private Long epoch;
}
