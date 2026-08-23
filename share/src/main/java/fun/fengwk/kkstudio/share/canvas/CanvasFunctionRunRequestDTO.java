package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** Function start/cancel 的严格 requestId body。 */
@Data
public class CanvasFunctionRunRequestDTO {
  private String requestId;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
