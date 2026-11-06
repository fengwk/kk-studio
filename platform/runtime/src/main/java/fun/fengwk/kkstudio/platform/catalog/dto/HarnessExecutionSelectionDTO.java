package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/** 冻结进 branch settings 快照的执行选择：driver 名 + driver-specific 设置。 */
@Data
public class HarnessExecutionSelectionDTO {

  /** 必需 Driver id。 */
  private String driver;

  /** 必需 driver-specific 设置对象；保留值为 null 的键，不能改变 Driver 的 settings 形状。 */
  @JsonInclude(content = JsonInclude.Include.ALWAYS)
  private Map<String, Object> settings;
}
