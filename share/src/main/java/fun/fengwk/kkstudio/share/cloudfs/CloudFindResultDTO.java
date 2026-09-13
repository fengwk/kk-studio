package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 发现文件结果响应体（{@code POST /api/cloud/find}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudFindResultDTO {

  /** 匹配到的路径列表（目录以 {@code /} 结尾）。 */
  private List<String> paths;

  /** 是否因达到 limit 上限而提前截断。 */
  private Boolean limited;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
