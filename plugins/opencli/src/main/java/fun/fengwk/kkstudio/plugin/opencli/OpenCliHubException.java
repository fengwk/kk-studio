package fun.fengwk.kkstudio.plugin.opencli;

import java.util.Objects;

/**
 * OpenCLI Hub transport、wire contract 或 provider execution 的 fail-closed 错误。
 *
 * <p>错误消息始终去敏：绝不携带凭据、私有身份、远端正文或远端 URL。{@link #kind()} 是真实错误边界给出的最小类型化分类，供显式连接检查把可观察失败投影为类型化维度，
 * 调用方绝不从消息文本猜分类；未归类的失败取 {@link Kind#INDETERMINATE}。
 */
public class OpenCliHubException extends RuntimeException {

  /**
   * 最小失败分类。
   *
   * <p>{@code TRANSPORT} 只表示可证明「HTTP 响应尚未取得」的连接建立失败；其它无法区分的失败一律保持 {@code INDETERMINATE}。
   */
  public enum Kind {

    /** 本地没有可用（或可用形态合法）的凭据，请求未发出。 */
    CREDENTIAL_UNAVAILABLE,

    /** 集成未启用，请求未发出。 */
    DISABLED,

    /** 已确认收到远端 HTTP 响应且远端确定性拒绝本次凭据（401/403）。 */
    AUTH_REJECTED,

    /** 已确认收到远端 HTTP 响应，但状态码不是成功。 */
    HTTP_STATUS,

    /** 已确认收到远端 HTTP 响应，但违反 wire 契约。 */
    PROTOCOL,

    /** 可证明请求从未与远端建立连接。 */
    TRANSPORT,

    /** 无法证明可达、不可达或认证结论。 */
    INDETERMINATE
  }

  private static final Kind DEFAULT_KIND = Kind.INDETERMINATE;

  private final Kind kind;

  public OpenCliHubException(String message) {
    this(DEFAULT_KIND, message, null);
  }

  public OpenCliHubException(String message, Throwable cause) {
    this(DEFAULT_KIND, message, cause);
  }

  public OpenCliHubException(Kind kind, String message) {
    this(kind, message, null);
  }

  public OpenCliHubException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  /** 本失败的分类；消息文本不是分类依据。 */
  public Kind kind() {
    return kind;
  }
}
