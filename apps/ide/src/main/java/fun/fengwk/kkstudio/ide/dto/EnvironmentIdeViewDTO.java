package fun.fengwk.kkstudio.ide.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * IDE 视图响应：只包含 Daemon 确认的绑定身份、目录与受管 workspace。
 *
 * <p>{@code bindingEpoch} 是正整数；{@code daemonInstanceId}/{@code bindingEpoch} 可作为恢复身份进入
 * sessionStorage。零根视图没有目录，{@code directory} 为 {@code null}（HTTP 层可缺省）；{@code workspacePath} 始终是
 * Daemon 登记的 {@code .code-workspace}。{@code entryUrl}、bootstrap token 与 cookie 属于运行凭据，不进入任何可持久化快照。
 */
@Data
public class EnvironmentIdeViewDTO {

  private String viewId;
  private String environmentId;
  private String browserSessionId;
  private String eventNodeId;
  private String connectionId;
  private String claimId;
  private String daemonInstanceId;
  private Long bindingEpoch;
  private @Nullable String directory;
  private String workspacePath;
  private EnvironmentIdeViewState state;

  /** Studio 根相对的短时一次性 bootstrap 入口；只有 claim 成功时返回。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private @Nullable String entryUrl;
}
