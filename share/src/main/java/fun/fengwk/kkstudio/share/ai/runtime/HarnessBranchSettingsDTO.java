package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 单个 Entry branch 的完整设置快照。
 *
 * <p>{@code environmentName} 是可空的 canonical bounded 小写逻辑路由名称，是 durable 快照中的唯一路由身份。{@code
 * activeTools} 在转换边界处不可变（web mapper 始终执行复制）。
 */
@Data
public class HarnessBranchSettingsDTO {
  /** 可空的 live Environment 路由身份：canonical bounded 小写名称；null 表示未绑定 Environment，显示名永不进入该快照。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  /** 必填 Agent definition 名（canonical text，≤128 字符）。 */
  private String agentName;

  /** 必填 provider/model/variant 选择（三项均非空白）。 */
  private HarnessModelSelectionDTO model;

  /** 激活工具短名列表（顺序敏感，元素 ≤128 字符、去重）；默认不可变空列表。 */
  private List<String> activeTools = List.of();

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown branch settings field: " + name);
  }
}
