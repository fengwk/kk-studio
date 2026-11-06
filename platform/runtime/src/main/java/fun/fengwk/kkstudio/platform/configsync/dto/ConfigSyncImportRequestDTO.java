package fun.fengwk.kkstudio.platform.configsync.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** {@code POST /api/platform/config-sync/import} 请求体：待导入的 YAML 文本；服务端不记录其内容。 */
@Data
public class ConfigSyncImportRequestDTO {

  private String yaml;

  /** 只有用户明确接受跳过项时才允许部分导入，缺省为 false。 */
  private @Nullable Boolean allowPartial;
}
