package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** 可离线选择的工具 catalog 条目。 */
@Data
public class ToolCatalogEntryDTO {
  /** 模型可见工具名：以字母开头，仅包含字母、数字、{@code _} 或 {@code -}；全局唯一。 */
  private String name;

  /** 工具描述（非空白）。 */
  private String description;

  /** 是否需要绑定 Environment 执行。 */
  private boolean environmentRequired;

  /** 目标 Environment UUID（精确要求环境时非空，通用环境工具或 Host 工具为 null）。 */
  private String environmentId;
}
