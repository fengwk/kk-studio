package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * 统一 Model 资源：单 MODEL 或按序降级的 GROUP，二者共享唯一的全局逻辑名称空间。
 *
 * <p>MODEL 携带 Provider、上游模型标识与可执行配置；GROUP 只携带成员编排，能力摘要由成员派生。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SingleModelDTO.class, name = "MODEL"),
  @JsonSubTypes.Type(value = ModelGroupDTO.class, name = "GROUP")
})
public sealed interface ModelDTO permits SingleModelDTO, ModelGroupDTO {

  String getName();

  String getDescription();

  ModelConfigDTO getConfig();

  String getVersion();

  Instant getCreatedAt();

  Instant getUpdatedAt();
}
