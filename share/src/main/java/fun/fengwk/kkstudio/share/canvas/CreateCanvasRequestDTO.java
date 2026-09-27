package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

@Data
public class CreateCanvasRequestDTO {

  /** 必填画布标题：trim 后不得为空白，服务端另行拒绝控制字符与超长标题。 */
  private String title;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
