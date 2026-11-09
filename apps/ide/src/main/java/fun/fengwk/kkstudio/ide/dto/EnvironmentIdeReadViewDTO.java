package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 纯读单个 IDE 视图的显式查询参数。
 *
 * <p>{@code viewId} 在路径中；其余字段全部由调用方显式声明，并须与当前已 ACK 的浏览器使用意图一致。已绑定操作必须携带 {@code
 * expectedDaemonInstanceId}，初始 create 发现资源时可以缺省。
 */
@Data
public class EnvironmentIdeReadViewDTO {

  private String browserSessionId;
  private String eventNodeId;
  private String connectionId;
  private String claimId;
  private @Nullable String expectedDaemonInstanceId;
}
