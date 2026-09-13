package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 正则/字面量文本内容检索请求体（{@code POST /api/cloud/grep}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudGrepRequestDTO {

  /** 检索起始路径。 */
  private String path;

  /** RE2 正则或字面量子串模式。 */
  private String pattern;

  /** 可选的文件名/路径过滤 glob 模式。 */
  private String include;

  /** 是否忽略大小写（默认为 false）。 */
  private Boolean ignoreCase;

  /** 是否执行精确字面量匹配（默认为 false）。 */
  private Boolean literal;

  /** 是否开启多行匹配（默认为 false）。 */
  private Boolean multiline;

  /** 结果行数上限（默认为 100）。 */
  private Integer limit;

  /** 超时秒数（默认为 15）。 */
  private Integer timeoutSeconds;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
