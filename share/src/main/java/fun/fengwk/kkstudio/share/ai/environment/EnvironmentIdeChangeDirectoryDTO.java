package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 切换 IDE view 目录的请求；必须携带当前文档与绑定代际，dirty 文档由 Daemon 明确拒绝。 */
@Data
public class EnvironmentIdeChangeDirectoryDTO {

  private String documentId;
  private String bindingEpoch;
  private String directory;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide directory field: " + fieldName);
  }
}
