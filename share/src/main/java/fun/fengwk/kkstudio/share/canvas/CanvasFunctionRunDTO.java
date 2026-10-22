package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class CanvasFunctionRunDTO {
  private String nodeId;
  private String requestId;
  private String status;
  private String stage;

  /** required-nullable：无错误时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String error;

  private String updatedAt;
}
