package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文本节点行窗口公开表示。
 *
 * <p>基于 1-based offset、有界行数限制、有界内联字节预算以及 2000 code points 行截断标记展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudTextWindowDTO {

  /** 文本节点的当前 revision；Blob 文本窗口时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String revision;

  /** 1-based 起始行号。 */
  private Integer offset;

  /** 请求的行数上限。 */
  private Integer limit;

  /** 文本的实际物理总行数。 */
  private Integer totalLines;

  /** 若后续仍有未读行则为下一窗口 1-based 行号；否则为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Integer nextOffset;

  /** 原始内容是否以换行符结尾。 */
  private Boolean endsWithNewline;

  /** 当前窗口的各行文本结构列表（包含 lineNumber, content, truncated）。 */
  private List<CloudTextLineDTO> lines;

  /** 拼接后的当前窗口文本内容（便利字段）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String content;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
