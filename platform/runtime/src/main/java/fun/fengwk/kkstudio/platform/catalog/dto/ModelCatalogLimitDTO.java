package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 目录候选的 Token 限制；目录未声明或格式错误的一侧为 null，填表时由用户补齐。 */
@Data
public class ModelCatalogLimitDTO {

  private @Nullable Integer context;

  private @Nullable Integer output;
}
