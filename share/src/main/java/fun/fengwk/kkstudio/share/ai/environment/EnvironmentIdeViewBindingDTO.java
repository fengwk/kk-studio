package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 需要当前文档与绑定代际的视图操作（聚焦终端、显式 detach）；旧代际不可作用于新绑定。 */
@Data
public class EnvironmentIdeViewBindingDTO {

  private String documentId;
  private String bindingEpoch;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide binding field: " + fieldName);
  }
}
