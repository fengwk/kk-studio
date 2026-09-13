package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 文本窗口单行公开表示。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudTextLineDTO {

  /** 1-based 行号。 */
  private Integer lineNumber;

  /** 单行文本内容。 */
  private String content;

  /** 是否被截断（如超过 2000 码点或预算截断）。 */
  private Boolean truncated;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
