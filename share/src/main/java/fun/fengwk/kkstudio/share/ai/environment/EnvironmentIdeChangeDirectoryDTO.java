package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 切换 IDE view 目录的请求；dirty 文档由 Daemon 明确拒绝，Server 不偷偷丢弃改动。 */
@Data
public class EnvironmentIdeChangeDirectoryDTO {

  private String directory;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide directory field: " + fieldName);
  }
}
