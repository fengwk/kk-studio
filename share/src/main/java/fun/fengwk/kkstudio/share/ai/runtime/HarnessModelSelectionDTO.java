package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 冻结进 branch settings 快照的不可变 provider/model/variant 选择。 */
@Data
public class HarnessModelSelectionDTO {
  /** 必填 Provider 资源名（canonical text，≤128 字符）。 */
  private String providerName;

  /** 必填模型名（canonical text，≤128 字符）。 */
  private String modelName;

  /** 必填模型变体 id（canonical text，≤128 字符），必须是模型 config 中存在的 variants[].id。 */
  private String variant;
}
