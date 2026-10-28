package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * IDE 视图响应：只包含 Daemon 确认的绑定身份、目录与受管 workspace。
 *
 * <p>{@code bindingEpoch} 是规范正十进制字符串；{@code daemonInstanceId}/{@code bindingEpoch}
 * 只进入内存路由与短期入口，不进入浏览器持久存储。
 */
@Data
public class EnvironmentIdeViewDTO {

  private String viewId;
  private String environmentId;
  private String documentId;
  private String eventNodeId;
  private String connectionId;
  private String claimId;
  private String daemonInstanceId;
  private String bindingEpoch;
  private String directory;
  private String workspacePath;
  private String state;

  /** Studio 根相对的短时一次性 bootstrap 入口；只有 claim 成功时返回。 */
  private @Nullable String entryUrl;
}
