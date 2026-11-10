package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;

/** 可离线选择的工具 catalog 条目。 */
@Data
public class ToolCatalogEntryDTO {

  /** 模型可见工具名：以字母开头，仅包含字母、数字、{@code _} 或 {@code -}；全局唯一。 */
  private String name;

  /** 工具描述（非空白）。 */
  private String description;

  /** Tool 与 Environment 的关系。 */
  private @Nullable EnvironmentSupport environmentSupport;

  /** 工具提供者；目录条目必然来自某个 provider，因此序列化必须始终输出。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable ToolProviderDTO provider;
}
