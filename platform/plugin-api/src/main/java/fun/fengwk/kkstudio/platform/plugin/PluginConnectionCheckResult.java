package fun.fengwk.kkstudio.platform.plugin;

import java.util.Objects;

/**
 * 一次显式连接检查的类型化结果：只有三个维度，没有自由文本、URI、远端正文或秘密。
 *
 * <p>三个维度各自独立陈述本次真正核验到的结论：{@code reachability} 是网络可达性，{@code authentication} 是本次凭据是否被接受（{@code
 * UNAVAILABLE} 只表示本地没有可用凭据，远端故障绝不用它代言），{@code browserSession} 是浏览器登录会话。未核验的维度明确取 {@code
 * NOT_CHECKED}，无法证明的取 {@code INDETERMINATE}，绝不以健康值代言未知。
 */
public record PluginConnectionCheckResult(
    Reachability reachability, Authentication authentication, BrowserSession browserSession) {

  public PluginConnectionCheckResult {
    Objects.requireNonNull(reachability, "reachability");
    Objects.requireNonNull(authentication, "authentication");
    Objects.requireNonNull(browserSession, "browserSession");
  }

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

    /** 本次没有可判定的认证尝试（例如未启用）。 */
    NOT_CHECKED
  }

  /** 浏览器登录会话维度；当前没有核验过登录查询协议，因此只有唯一合法值。 */
  public enum BrowserSession {

    /** 未核验浏览器登录会话，绝不声称已登录。 */
    UNKNOWN
  }
}
