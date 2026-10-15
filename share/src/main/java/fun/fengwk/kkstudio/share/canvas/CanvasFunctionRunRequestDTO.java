package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** Function start 的严格 requestId + expectedVersion body。 */
@Data
public class CanvasFunctionRunRequestDTO {
  private String requestId;

  /** 客户端从目录看到的函数版本；必填，与执行接受的冻结定义必须完全一致。 */
  private CanvasFunctionVersionDTO expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
