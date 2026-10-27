package fun.fengwk.kkstudio.ide.mux;

/** RESET 的有界安全错误；message 不得包含 URL、凭据或控制字符。 */
public record MuxReset(String code, String message) {

  public MuxReset {
    code = MuxWireRules.requireResetCode(code);
    message = MuxWireRules.requireResetMessage(message);
  }
}
