package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** 可离线选择的工具 catalog 条目。 */
@Data
public class ToolCatalogEntryDTO {

  /** 模型可见工具名：以字母开头，仅包含字母、数字、{@code _} 或 {@code -}；全局唯一。 */
  private String name;

  /** 工具描述（非空白）。 */
  private String description;

  /** Tool 与 Environment 的关系，取 {@link EnvironmentSupportDTO} 的名字。 */
  private EnvironmentSupportDTO environmentSupport;

  /** 精确要求的目标 Environment UUID（canonical UUID 文本）；只接受任意已绑定 Environment 时为 null。 */
  private String requiredEnvironmentId;
}
