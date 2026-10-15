package fun.fengwk.kkstudio.plugin.opencli;

import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheck;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult.Authentication;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult.BrowserSession;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult.Reachability;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubException.Kind;

import java.util.Objects;

/**
 * OpenCLI 的显式只读连接检查投影。
 *
 * <p>它复用 {@link OpenCliHubClient#probeConnection()} 的 scoped 认证与有界只读请求，只把 {@link
 * OpenCliHubException} 已经给出的 {@link Kind 类型化分类}投影为三个维度：认证只有「远端确定性拒绝本次凭据」才是 {@code
 * REJECTED}、只有成功合法信封才是 {@code ACCEPTED}，本地没有可用凭据才是 {@code UNAVAILABLE}；HTTP 其它状态、协议不符、纯连接失败与无法证明的分类
 * 都只陈述可达性，认证保持 {@code NOT_CHECKED}，绝不把远端故障说成没有凭据或认证成功。
 *
 * <p>它绝不读取异常消息来猜分类，也不吞掉未定义的运行时失败：只有 {@link OpenCliHubException} 被投影为结果，其它异常与进程级 {@code Error}
 * 原样上抛，中断标志保持。结果绝不携带异常文本、远端 URI、远端正文或凭据；未核验浏览器登录协议，因此 {@code browserSession} 恒为 {@code UNKNOWN}。
 */
final class OpenCliConnectionCheck implements PluginConnectionCheck {

  private final OpenCliHubClient client;

  OpenCliConnectionCheck(OpenCliHubClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public PluginConnectionCheckResult check() {
    try {
      client.probeConnection();
      return result(Reachability.REACHABLE, Authentication.ACCEPTED);
    } catch (OpenCliHubException failure) {
      return fromKind(failure.kind());
    }
  }

  private static PluginConnectionCheckResult fromKind(Kind kind) {
    return switch (kind) {
      case DISABLED -> result(Reachability.NOT_CHECKED, Authentication.NOT_CHECKED);
      case CREDENTIAL_UNAVAILABLE -> result(Reachability.NOT_CHECKED, Authentication.UNAVAILABLE);
      case AUTH_REJECTED -> result(Reachability.REACHABLE, Authentication.REJECTED);
      case HTTP_STATUS, PROTOCOL -> result(Reachability.REACHABLE, Authentication.NOT_CHECKED);
      case TRANSPORT -> result(Reachability.UNREACHABLE, Authentication.NOT_CHECKED);
      case INDETERMINATE -> result(Reachability.INDETERMINATE, Authentication.NOT_CHECKED);
    };
  }

  private static PluginConnectionCheckResult result(
      Reachability reachability, Authentication auth) {
    return new PluginConnectionCheckResult(reachability, auth, BrowserSession.UNKNOWN);
  }
}
