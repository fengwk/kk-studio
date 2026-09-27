package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Canvas 聚合的持久化头。{@code revision} 是单调递增的同步位置，也是 command 接受回执与 patch 的公共坐标系，wire 为规范非负十进制字符串；
 * 它不是普通编辑的整图前置版本。
 */
@Data
public class CanvasDocumentDTO {

  private String id;

  private String title;

  private String revision;

  private String createdAt;

  private String updatedAt;

  @JsonAnySetter
  public void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }
}
