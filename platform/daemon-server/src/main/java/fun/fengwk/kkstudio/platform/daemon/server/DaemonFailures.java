package fun.fengwk.kkstudio.platform.daemon.server;

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
}
