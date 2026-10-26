package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.CloseStatus;

import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * IDE 数据流收尾诊断：把关闭状态与 Daemon 对 {@code OPEN_STREAM} 的失败回执压成安全摘要，供每条流结束时写一行日志，使线上能判定关闭方与原因。
 *
 * <p>只记录数值关闭码与失败分类。关闭原因、Daemon 回执和异常消息都可能包含对端提供的路径或凭据，不进入日志。
 */
final class IdeStreamDiagnostics {

  /** Daemon 侧异常结束时等待其 {@code OPEN_STREAM} 回执的上限：回执通常紧随反向流关闭到达，之后才可放弃该调用。 */
  static final Duration REPLY_GRACE = Duration.ofSeconds(2);

  private IdeStreamDiagnostics() {}

  /** 关闭状态的单行摘要；没有关闭状态时为 {@code -}。 */
  static String describe(CloseStatus status) {
    if (status == null) {
      return "-";
    }
    return String.valueOf(status.getCode());
  }

  /**
   * Daemon 对 {@code OPEN_STREAM} 的失败分类；不记录回执中的自由文本。成功、尚未到达、已取消或请求未发出时为 {@code -}。
   *
   * @param wait 最长等待时间；零表示只读取已到达的回执
   */
  static String replyFailure(IdeRequest request, Duration wait) {
    if (request == null) {
      return "-";
    }
    try {
      IdeReply reply =
          wait.isZero()
              ? request.reply().getNow(null)
              : request.reply().get(wait.toMillis(), TimeUnit.MILLISECONDS);
      return reply == null || reply.ok() ? "-" : "REJECTED";
    } catch (TimeoutException
        | CancellationException
        | CompletionException
        | ExecutionException error) {
      return "-";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "-";
    }
  }
}
