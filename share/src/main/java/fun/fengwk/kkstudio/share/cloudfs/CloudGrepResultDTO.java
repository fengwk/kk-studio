package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Grep 检索结果响应体（{@code POST /api/cloud/grep}）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudGrepResultDTO {

  /** 匹配项列表。 */
  private List<CloudGrepMatchDTO> matches;

  /** 是否因达到 limit 上限而提前截断。 */
  private Boolean limited;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
