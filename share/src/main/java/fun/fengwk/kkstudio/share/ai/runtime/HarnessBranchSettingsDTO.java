package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 单个 Entry branch 的完整设置快照。
 *
 * <p>{@code environmentId} 是可空的 canonical lowercase UUID 路由身份；显示名永不进入该 durable 快照。{@code
 * activeTools} 在转换边界处不可变（web mapper 始终执行复制）。
 */
@Data
public class HarnessBranchSettingsDTO {
  /**
   * 可空的 live Environment 路由身份：canonical lowercase UUID（非 nil）；null 表示未绑定 Environment，显示名永不进入该快照。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentId;

  /** 必填 Agent definition 名（canonical text，≤128 字符）。 */
  private String agentName;

  /** 必填 provider/model/variant 选择（三项均非空白）。 */
  private HarnessModelSelectionDTO model;

  /** 必填 thinking level（canonical 名）：冻结的 reasoning effort 覆盖，运行时覆盖到所选 variant 的 reasoningEffort。 */
  private String thinkingLevel;

  /** 激活工具短名列表（顺序敏感，元素 ≤128 字符、去重）；默认不可变空列表。 */
  private List<String> activeTools = List.of();
}
