package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * {@code PUT /api/platform/models/{name}} 的请求体：整体替换逻辑名、描述与类型专属的编排配置。
 *
 * <p>MODEL 与 GROUP 的类型不可修改；{@code expectedVersion} 必填。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SingleModelUpdateDTO.class, name = "MODEL"),
  @JsonSubTypes.Type(value = ModelGroupUpdateDTO.class, name = "GROUP")
})
public sealed interface ModelUpdateDTO permits SingleModelUpdateDTO, ModelGroupUpdateDTO {

  String getName();

  Long getExpectedVersion();
}
