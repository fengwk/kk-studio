package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 写入/覆盖文本文件请求体（{@code PUT /api/cloud/text}）。
 *
 * <p>{@code expectedRevision} 采用规范非负十进制字符串：{@code "0"} 表示创建新文件，{@code "N"}（N > 0）表示替换现有当前版本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudTextWriteRequestDTO {

  /** 目标文本文件的规范虚拟绝对路径。 */
  private String path;

  /** 权威 UTF-8 文本内容（最大 1 MiB）。 */
  private String content;

  /** 预期的当前文本版本号（规范非负十进制字符串）。 */
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
