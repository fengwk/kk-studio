package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

@Data
public class CreateCanvasRequestDTO {
  /** 可选画布标题：trim 后为空白时使用默认标题（未命名画布）。 */
  private String title;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
