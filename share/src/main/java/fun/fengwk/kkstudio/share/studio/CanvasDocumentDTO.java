package fun.fengwk.kkstudio.share.studio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Canvas 聚合的持久化头。version 是单调递增的 graph 版本，也是 command expected 游标与 patch base/version 的公共坐标系，wire
 * 为规范非负十进制字符串。
 */
@Data
public class CanvasDocumentDTO {

  private String id;

  private String title;

  private String version;

  private String createdAt;

  private String updatedAt;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
