package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/** 产品命令批次的 owner wire DTO。 */
@Data
public class HarnessCommandOwnerDTO {

  /** owner 类型：CHAT、CANVAS 或 PROJECT。 */
  private String type;

  /** owner 的 canonical UUID string。 */
  private String id;

  @JsonSetter("type")
  public void setType(Object value) {
    this.type = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.type");
  }

  @JsonSetter("id")
  public void setId(Object value) {
    this.id = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.id");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown command owner field: " + name);
  }
}
