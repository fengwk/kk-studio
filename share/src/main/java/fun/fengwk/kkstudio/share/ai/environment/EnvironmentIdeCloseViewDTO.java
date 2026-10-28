package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 关闭 IDE 视图：必须携带当前文档/代际，{@code discard} 必须由显式放弃操作给出。 */
@Data
public class EnvironmentIdeCloseViewDTO {

  private String documentId;
  private String bindingEpoch;
  private Boolean discard;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide close field: " + fieldName);
  }
}
