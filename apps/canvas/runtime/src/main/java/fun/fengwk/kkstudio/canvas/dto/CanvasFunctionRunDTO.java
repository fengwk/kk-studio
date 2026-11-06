package fun.fengwk.kkstudio.canvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;

import java.time.Instant;

@Data
public class CanvasFunctionRunDTO {
  private String nodeId;
  private String requestId;
  private CanvasFunctionRunStatus status;
  private String stage;

  /** required-nullable：无错误时必须显式输出 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String error;

  private Instant updatedAt;
}
