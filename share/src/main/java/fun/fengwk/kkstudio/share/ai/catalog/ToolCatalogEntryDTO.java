package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** 可离线选择的工具 catalog 条目。 */
@Data
public class ToolCatalogEntryDTO {
  /** 全局稳定 Agent Tool ID。 */
  private String id;

  /** 执行后端，取 {@code AgentToolBackend} 枚举名。 */
  private String backend;

  /** 工具规范名：以字母开头，仅包含字母、数字、{@code _} 或 {@code -}。 */
  private String name;

  /** 工具版本字符串（非空白）。 */
  private String version;

  /** 工具类型，取 {@code ToolType} 枚举名：PLATFORM（平台内置）或 ENVIRONMENT（由 live Environment 提供）。 */
  private String type;

  /** 工具描述（非空白）。 */
  private String description;
}
