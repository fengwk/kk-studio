package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 创建统一 Model（MODEL 或 GROUP）的请求体。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SingleModelCreateDTO.class, name = "MODEL"),
  @JsonSubTypes.Type(value = ModelGroupCreateDTO.class, name = "GROUP")
})
public sealed interface ModelCreateDTO permits SingleModelCreateDTO, ModelGroupCreateDTO {

  String getName();
}
