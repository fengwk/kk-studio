package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Function cancel 的严格 requestId body。
 *
 * <p>cancel 不是 start，不携带目录版本围栏：只按当前 Run 的精确 requestId 收敛终态。
 */
@Data
public class CanvasFunctionCancelRequestDTO {

  private String requestId;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
