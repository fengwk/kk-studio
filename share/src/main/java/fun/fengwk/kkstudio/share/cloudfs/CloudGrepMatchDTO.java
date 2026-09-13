package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Grep 命中匹配行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudGrepMatchDTO {

  /** 命中文件的规范虚拟绝对路径。 */
  private String path;

  /** 1-based 行号。 */
  private Integer lineNumber;

  /** 包含真实匹配项的有界摘录（最多 500 code points）。 */
  private String content;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
