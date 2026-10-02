package fun.fengwk.kkstudio.harness.daemon;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 机器启动入口的参数传输：绕过 Windows JDK launcher 把 Unicode 命令行转为 ANSI 时的有损转换。
 *
 * <p>只有首位 {@code --base64-args} 触发一次解码，其后每个 token 都是一个原始应用参数的 UTF-8 Base64。
 * 这只是编码而非加密，不增加配置来源；解码后的参数仍由现有 CLI 规则校验。
 */
public final class DaemonArguments {

  private static final String FLAG = "--base64-args";
  private static final String INVALID = "invalid daemon argument transport";

  private DaemonArguments() {}

  /** 普通 CLI 原样返回；编码输入严格拒绝非法 Base64、非法 UTF-8、null 和 NUL，不泄露原值或异常 cause。 */
  public static String[] decode(String[] args) {
    if (args == null) {
      throw new IllegalArgumentException(INVALID);
    }
    boolean encoded = args.length > 0 && FLAG.equals(args[0]);
    String[] decoded = encoded ? new String[args.length - 1] : args;
    for (int index = encoded ? 1 : 0; index < args.length; index++) {
      String value = args[index];
      if (value == null) {
        throw new IllegalArgumentException(INVALID);
      }
      if (encoded) {
        try {
          value =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(Base64.getDecoder().decode(value)))
                  .toString();
        } catch (IllegalArgumentException | CharacterCodingException error) {
          throw new IllegalArgumentException(INVALID);
        }
      }
      if (value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException(INVALID);
      }
      if (encoded) {
        decoded[index - 1] = value;
      }
    }
    return decoded;
  }
}
