package fun.fengwk.kkstudio.ide.mux;

import java.util.List;

/** HEADERS 元数据：状态码与有序多值响应 headers。 */
public record MuxHeaders(int status, List<MuxHeader> headers) {

  public MuxHeaders {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("status must be within [100, 599]");
    }
    headers = MuxHeader.copyHeaders(headers);
  }

  /** HTTP 模式使用任意合法状态码；WS 模式在引擎边界进一步要求 101。 */
  public static MuxHeaders http(int status, List<MuxHeader> headers) {
    return new MuxHeaders(status, headers);
  }

  /** WS 上游握手成功固定为 101。 */
  public static MuxHeaders webSocket(List<MuxHeader> headers) {
    return new MuxHeaders(101, headers);
  }
}
