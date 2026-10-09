package fun.fengwk.kkstudio.harness.environment.terminal;

/**
 * 终端控制协议的固定错误码。
 *
 * <p>它是错误回执里唯一的、可判定的分类信息：不携带自由文本，也不包装原始异常或载荷，避免把服务端内部细节泄漏到 wire。
 */
public enum ErrorCode {
  INVALID_REQUEST,
  TERMINAL_NOT_FOUND,
  DAEMON_MISMATCH,
  REQUEST_CONFLICT,
  STREAM_NOT_FOUND,
  VIEW_NOT_APPLIED,
  STALE_MODE,
  BUSY,
  ROUTE_UNAVAILABLE,
  BACKPRESSURE,
  RUNTIME_FAILED,
  OUTCOME_UNKNOWN
}
