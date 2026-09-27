package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Canvas 前进事件 payload：revision 前进提示（规范非负十进制字符串），客户端随后按自身最后已知 revision 拉取快照。 'resync' 事件无
 * payload，表示需要全量快照。字段名与坐标系 {@code canvas_document.revision} 一致，不使用 version 别名。
 */
@Data
public class CanvasVersionEventDTO {

  private String revision;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
