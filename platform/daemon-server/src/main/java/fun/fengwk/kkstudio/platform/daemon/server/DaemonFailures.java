package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Daemon 核心的统一失败日志。
 *
 * <p>所有 core 路径都用同一个固定形状记录可安全外发的失败：只写阶段与异常类名，绝不记录原始 throwable、message 或 cause，因为异常正文可能带有凭据或远端内容。
 *
 * <p>日志器仍按调用方类名创建，使诊断按核心环节归类。
 */
final class DaemonFailures {

  private DaemonFailures() {}

  /** 以 {@code owner} 的名义记录一次失败；{@code stage} 只放固定短语与实体标识，绝不放异常正文。 */
  static void log(Class<?> owner, String stage, Throwable failure) {
    Logger logger = System.getLogger(owner.getName());
    logger.log(
        Level.WARNING,
        "environment daemon " + stage + " failed: " + failure.getClass().getSimpleName());
  }

  /**
   * 记录一次控制协议校验器拒绝的字段路径与规则。
   *
   * <p>只用于确认异常来自协议校验器的路径（如 {@code ENVIRONMENT_CONFIG} 出站成帧）：这些拒绝的正文由校验器用固定字段路径与固定规则短语构造，
   * 不含输入值、远端内容或凭据。同一异常类型的其它来源（本地状态与存储失败）可能带内部细节，必须走 {@link #log} 只记异常类名。
   */
  static void logRejection(Class<?> owner, String stage, ControlProtocolException rejection) {
    Logger logger = System.getLogger(owner.getName());
    logger.log(
        Level.WARNING, "environment daemon " + stage + " rejected: " + rejection.getMessage());
  }
}
