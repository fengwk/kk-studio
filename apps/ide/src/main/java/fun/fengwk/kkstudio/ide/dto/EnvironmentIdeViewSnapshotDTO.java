package fun.fengwk.kkstudio.ide.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 单个 IDE 资源与当前绑定的权威快照；不存在 view 是有效 absent 事实，不是命令未执行的证明。
 *
 * <p>快照不生成 {@code entryUrl}，也不套用通用响应信封。{@code view} 为 null 时仍显式返回该字段。
 */
@Data
public class EnvironmentIdeViewSnapshotDTO {

  private String daemonInstanceId;
  private Long revision;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable EnvironmentIdeViewDTO view;
}
