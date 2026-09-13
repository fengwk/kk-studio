package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 精确替换文本内容请求体（{@code PATCH /api/cloud/text}）。
 *
 * <p>{@code expectedRevision} 必须大于 0；{@code oldString} 必须非空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudTextEditRequestDTO {

  /** 目标文本文件的规范虚拟绝对路径。 */
  private String path;

  /** 待匹配替换的精确非空子串。 */
  private String oldString;

  /** 替换后的新子串。 */
  private String newString;

  /** 是否替换所有匹配项；默认为 false。 */
  private Boolean replaceAll;

  /** 预期的当前文本版本号（规范非负十进制字符串，必须 > 0）。 */
  private String expectedRevision;

  @JsonSetter("expectedRevision")
  public void setExpectedRevision(Object value) {
    if (value != null && !(value instanceof String)) {
      throw new IllegalArgumentException("expectedRevision must be a JSON string");
    }
    this.expectedRevision = (String) value;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
