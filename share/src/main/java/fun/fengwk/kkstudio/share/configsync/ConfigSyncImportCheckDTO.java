package fun.fengwk.kkstudio.share.configsync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 导入预览：将新增、覆盖与跳过的条目；不含配置值，不冻结目标状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigSyncImportCheckDTO {

  private List<ConfigSyncRef> created;

  private List<ConfigSyncRef> updated;

  private List<ConfigSyncSkipped> skipped;
}
