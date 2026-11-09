package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 打开或恢复一个 IDE 视图的请求。
 *
 * <p>{@code viewId}/{@code browserSessionId}/{@code eventNodeId}/{@code connectionId}/{@code
 * claimId} 均为规范小写 UUID；{@code expectedDaemonInstanceId} 固定命令目标进程代次；{@code mode} 为 {@code
 * create}/{@code resume}，create 的 {@code expectedBindingEpoch} 必须为 0，resume 必须为正。目录只作为 create
 * 的候选输入，最终目录与 workspace 由 Daemon 回执确认。
 */
@Data
public class EnvironmentIdeOpenViewDTO {

  private String viewId;
  private String browserSessionId;
  private String eventNodeId;
  private String connectionId;
  private String claimId;
  private EnvironmentIdeOpenMode mode;
  private Long expectedBindingEpoch;
  private String expectedDaemonInstanceId;
  private @Nullable String directory;
}
