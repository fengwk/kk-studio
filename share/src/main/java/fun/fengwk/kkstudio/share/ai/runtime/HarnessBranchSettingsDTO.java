package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 单个 Entry branch 的设置快照。 */
@Data
public class HarnessBranchSettingsDTO {

  /** 可空的 workspace path；null 表示未绑定 workspace。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String workspacePath;

  /** 必填 Agent definition 名（canonical text，≤128 字符）。 */
  private String agentName;

  /** 必填 provider/model/variant 选择（三项均非空白）。 */
  private HarnessModelSelectionDTO model;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown branch settings field: " + name);
  }
}
