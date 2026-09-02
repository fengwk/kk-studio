package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/** 手动压缩请求；实际提交由 ThreadProcessor 的 version fence 决定。 */
@Data
public class HarnessThreadCompactDTO {

  /** exact non-negative decimal version cursor。 */
  private String expectedVersion;

  @JsonSetter("expectedVersion")
  public void setExpectedVersion(Object value) {
    this.expectedVersion = HarnessRuntimeDtoSupport.requireJsonString(value, "expectedVersion");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown compact field: " + name);
  }
}
