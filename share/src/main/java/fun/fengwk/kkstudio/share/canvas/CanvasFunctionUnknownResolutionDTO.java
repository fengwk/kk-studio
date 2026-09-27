package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 人工核查 UNKNOWN Function Run 的严格请求体。
 *
 * <p>{@code resolution} 只能是 RESUME / FAILED / CANCELLED；{@code verification}
 * 是人工核查事实的文本记录，必须非空并会被持久化。
 */
@Data
public class CanvasFunctionUnknownResolutionDTO {

  private String requestId;
  private String resolution;
  private String verification;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
