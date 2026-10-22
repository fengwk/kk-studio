package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 打开一个 IDE view 的请求：{@code viewId} 由前端 pane 分配，{@code directory} 缺省时由宿主校验 HOME 后决定。 */
@Data
public class EnvironmentIdeOpenViewDTO {

  private String viewId;
  private @Nullable String directory;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide view field: " + fieldName);
  }
}
