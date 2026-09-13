package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 按 glob 模式发现文件请求体（{@code POST /api/cloud/find}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudFindRequestDTO {

  /** 搜索起始目录路径。 */
  private String path;

  /** Glob 匹配模式，例如 {@code **&#47;*.java}。 */
  private String pattern;

  /** 结果上限（默认为 200）。 */
  private Integer limit;

  /** 超时秒数（默认为 15）。 */
  private Integer timeoutSeconds;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
