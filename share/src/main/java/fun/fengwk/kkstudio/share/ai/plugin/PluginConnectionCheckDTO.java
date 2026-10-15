package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;

import java.time.Instant;

/**
 * 一次显式连接检查的类型化结果投影。
 *
 * <p>只包含安装身份、本次检查所依据的已应用坐标、检查时刻与三个类型化维度：没有异常消息、远端 URI、远端正文或任何凭据；未核验的维度明确为 {@code NOT_CHECKED}，
 * 无法证明的维度明确为 {@code INDETERMINATE}，绝不以健康值代言未知。三个维度都是本模块自己的封闭枚举，wire 上只出现契约内的精确名称。
 */
@Data
public class PluginConnectionCheckDTO {

  /** 网络可达性维度。 */
  public enum Reachability {

    /** 已确认收到远端 HTTP 响应。 */
    REACHABLE,

    /** 已证明请求从未与远端建立连接。 */
    UNREACHABLE,

    /** 本次没有发起探测（例如未启用或没有可用凭据）。 */
    NOT_CHECKED,

    /** 已发起但无法证明可达或不可达。 */
    INDETERMINATE
  }

  /** 本次凭据的认证维度。 */
  public enum Authentication {

    /** 远端接受本次凭据。 */
    ACCEPTED,

    /** 远端确定性拒绝本次凭据。 */
    REJECTED,

    /** 本地没有可用凭据（例如未连接、密钥不可用）；远端故障绝不使用本值。 */
    UNAVAILABLE,

    /** 本次没有可判定的认证尝试（例如未启用），或无法得到认证结论。 */
    NOT_CHECKED
  }

  /** 浏览器登录会话维度；当前没有核验过登录查询协议，因此只有唯一合法值。 */
  public enum BrowserSession {

    /** 未核验浏览器登录会话，绝不声称已登录。 */
    UNKNOWN
  }

  /** Plugin 全局唯一不可变安装身份。 */
  private String pluginId;

  /** 本次检查所依据的已应用 epoch（canonical 非负十进制字符串）。 */
  private String appliedEpoch;

  /** 本次检查所依据的已应用配置修订（canonical 非负十进制字符串）。 */
  private String appliedRevision;

  /** 本次检查的执行时刻。 */
  private Instant checkedAt;

  /** 网络可达性。 */
  private Reachability reachability;

  /** 本次凭据的认证结论。 */
  private Authentication authentication;

  /** 浏览器登录会话。 */
  private BrowserSession browserSession;
}
