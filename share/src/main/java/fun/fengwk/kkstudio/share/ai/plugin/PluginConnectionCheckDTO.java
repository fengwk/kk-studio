package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;

import java.time.Instant;

/**
 * 一次显式连接检查的类型化结果投影。
 *
 * <p>只包含安装身份、本次检查所依据的已应用坐标、检查时刻与三个类型化维度：没有异常消息、远端 URI、远端正文或任何凭据；未核验的维度明确为 {@code NOT_CHECKED}，
 * 无法证明的维度明确为 {@code INDETERMINATE}，绝不以健康值代言未知。{@code browserSession} 当前只有 {@code UNKNOWN}。
 */
@Data
public class PluginConnectionCheckDTO {

  /** Plugin 全局唯一不可变安装身份。 */
  private String pluginId;

  /** 本次检查所依据的已应用 epoch（canonical 非负十进制字符串）。 */
  private String appliedEpoch;

  /** 本次检查所依据的已应用配置修订（canonical 非负十进制字符串）。 */
  private String appliedRevision;

  /** 本次检查的执行时刻。 */
  private Instant checkedAt;

  /** 网络可达性：{@code REACHABLE / UNREACHABLE / NOT_CHECKED / INDETERMINATE}。 */
  private String reachability;

  /** 本次凭据的认证结论：{@code ACCEPTED / REJECTED / UNAVAILABLE / NOT_CHECKED}。 */
  private String authentication;

  /** 浏览器登录会话：当前唯一合法值为 {@code UNKNOWN}，绝不声称已登录。 */
  private String browserSession;
}
