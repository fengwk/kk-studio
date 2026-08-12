package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Canvas 聚合的持久化头。version 是单调递增的 graph 版本，也是 command expected 游标与 patch base/version 的公共坐标系；
 * threadId 绑定本画布的 Harness Thread（canonical UUID 字符串），null 表示尚未创建（走 blank 首次发送流程）。
 */
@Data
public class CanvasDocumentDTO {

  private String id;

  private String title;

  private long version;

  private String threadId;

  private String createdAt;

  private String updatedAt;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
