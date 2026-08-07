package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** live Environment 发布的紧凑工具能力。 */
@Data
public class LiveEnvironmentToolDTO {
  /** 工具规范名（以字母开头，仅含字母、数字、{@code _} 或 {@code -}）。 */
  private String name;

  /** 工具版本字符串（非空白）。 */
  private String version;

  /** 工具描述（非空白）。 */
  private String description;
}
