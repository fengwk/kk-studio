package fun.fengwk.kkstudio.platform.configsync.dto;

import lombok.Data;

import java.util.List;

/** {@code POST /api/platform/config-sync/export} 请求体：待导出的条目选择；后端负责补齐依赖闭包。 */
@Data
public class ConfigSyncExportRequestDTO {

  private List<ConfigSyncRef> items;
}
