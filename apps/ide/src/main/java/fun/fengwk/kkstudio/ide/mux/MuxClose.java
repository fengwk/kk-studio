package fun.fengwk.kkstudio.ide.mux;

import java.nio.charset.StandardCharsets;

/** WebSocket CLOSE 语义：RFC 6455 可发送关闭码与至多 123 UTF-8 字节的原因。 */
public record MuxClose(int code, String reason) {

  public MuxClose {
    if (!MuxWireRules.validCloseCode(code)) {
      throw new IllegalArgumentException("close code is not permitted on the wire");
    }
    if (reason == null) {
      throw new IllegalArgumentException("close reason must not be null");
    }
    MuxWireRules.requireUnicodeScalars(reason, "close reason");
    if (reason.getBytes(StandardCharsets.UTF_8).length > MuxLimits.MAX_CLOSE_REASON_BYTES) {
      throw new IllegalArgumentException(
          "close reason exceeds " + MuxLimits.MAX_CLOSE_REASON_BYTES + " UTF-8 bytes");
    }
  }
}
