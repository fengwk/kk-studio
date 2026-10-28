package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 打开或恢复一个 IDE 视图的请求。
 *
 * <p>{@code viewId}/{@code documentId}/{@code eventNodeId}/{@code connectionId}/{@code claimId}
 * 均为规范小写 UUID； {@code mode} 为 {@code create}/{@code resume}。目录只作为 create 的候选输入，最终目录与 workspace 由
 * Daemon 回执确认。
 */
@Data
public class EnvironmentIdeOpenViewDTO {

  private String viewId;
  private String documentId;
  private String eventNodeId;
  private String connectionId;
  private String claimId;
  private String mode;
  private @Nullable String directory;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide view field: " + fieldName);
  }
}
