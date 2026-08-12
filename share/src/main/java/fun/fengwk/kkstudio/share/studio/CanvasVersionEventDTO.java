package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * SSE 'version' 事件 payload：version 前进提示，客户端随后按自身最后已知版本拉取 changes。 'resync' 事件无 payload，表示需要全量快照。
 */
@Data
public class CanvasVersionEventDTO {

  private long version;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
