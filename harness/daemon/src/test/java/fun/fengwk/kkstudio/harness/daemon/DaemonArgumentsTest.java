package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

/** 传输层保真与失败关闭；业务参数是否合法仍由 DaemonConfig 决定。 */
class DaemonArgumentsTest {

  /** 意图：中文、emoji、空值和命令行特殊字符按单个参数无损还原，而不是解析一段命令行。 */
  @Test
  void decodesEachUtf8ArgumentExactly() {
    String[] original = {
      "--note",
      "中文😀",
      "",
      "two words ",
      "embedded\"quote",
      "C:\\Program Files\\Java\\",
      "slashes\\\\\"before-quote",
      "&|%^",
      "\n\t"
    };
    assertArrayEquals(original, DaemonArguments.decode(encoded(original)));
    assertArrayEquals(new String[0], DaemonArguments.decode(encoded()));
  }

  /** 意图：普通 CLI 不变，非首位传输 flag 不会意外激活解码。 */
  @Test
  void preservesOrdinaryArgumentsAndOnlyChecksTheFirstFlag() {
    for (String[] args :
        new String[][] {{}, {"--note", "中文😀"}, {"--note", "--base64-args", "%%%%"}}) {
      assertSame(args, DaemonArguments.decode(args));
    }
  }

  /** 意图：错误诊断固定且没有 cause，非法输入及 decoder 异常不得扩散进日志。 */
  @Test
  void rejectsMalformedTransportWithoutLeakingInput() {
    for (String[] args :
        new String[][] {
          null,
          {null},
          {"ordinary", null},
          {"NUL\0value"},
          {"--base64-args", null},
          {"--base64-args", "private%%%"},
          {"--base64-args", "A"},
          {"--base64-args", "Y Q=="},
          {"--base64-args", "/w=="},
          {"--base64-args", "wK8="},
          {"--base64-args", "7aCA"},
          {"--base64-args", "4oI="},
          encoded("NUL\0value"),
          {"--base64-args", "--note", "text"}
        }) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> DaemonArguments.decode(args));
      assertEquals("invalid daemon argument transport", error.getMessage());
      assertNull(error.getCause());
    }
  }

  /** 意图：不递归解码、不允许编码与普通参数混用，也不绕过原配置的重复/缺失选项校验。 */
  @Test
  void decodedArgumentsStillFailClosedUnderExistingCliRules() {
    String[] nested = encoded("--base64-args", Base64.getEncoder().encodeToString(new byte[0]));
    assertArrayEquals(new String[] {"--base64-args", ""}, DaemonArguments.decode(nested));
    for (String[] original :
        new String[][] {
          {"--base64-args", ""},
          {"--help", "--version"},
          {"--config", "/one", "--config", "/two"},
          {"--config"},
          {"--config", "--check-config"},
          {"--config", "relative.json"}
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonConfig.fromArgs(DaemonArguments.decode(encoded(original))));
    }
  }

  static String[] encoded(String... args) {
    return Stream.concat(
            Stream.of("--base64-args"),
            Stream.of(args)
                .map(
                    value ->
                        Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))))
        .toArray(String[]::new);
  }
}
