package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 移动/重命名节点请求体（{@code POST /api/cloud/nodes/move}）。
 *
 * <p>{@code expectedVersion} 为源节点的预期元数据版本号（规范非负十进制字符串）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudNodeMoveRequestDTO {

  /** 源路径。 */
  private String sourcePath;

  /** 目标路径。 */
  private String destinationPath;

  /** 源节点的预期元数据版本号（规范非负十进制字符串）。 */
  private String expectedVersion;

  @JsonSetter("expectedVersion")
  public void setExpectedVersion(Object value) {
    if (value != null && !(value instanceof String)) {
      throw new IllegalArgumentException("expectedVersion must be a JSON string");
    }
    this.expectedVersion = (String) value;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
