package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/** 手动压缩请求；实际提交由 ThreadProcessor 的 revision fence 决定。 */
@Data
public class HarnessThreadCompactDTO {

  /** exact non-negative decimal revision cursor。 */
  private String expectedRevision;

  @JsonSetter("expectedRevision")
  public void setExpectedRevision(Object value) {
    this.expectedRevision = HarnessRuntimeDtoSupport.requireJsonString(value, "expectedRevision");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown compact field: " + name);
  }
}
