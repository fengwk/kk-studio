package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 用纯 Java 生成一份**逐字节已知**的大输出，作为能力测试里的「用户命令」。
 *
 * <p>「大输出不构成终止进程的理由」需要一个精确的标准：靠平台工具（{@code seq}、{@code head -c}、{@code awk}）生成时，输出形状取决 于该工具的实现与登录
 * shell 的启动噪声（macOS 的 {@code bash -lc} 会执行 profile），测试只能退化为「大致相等」。这个夹具 把形状声明清楚：先用一行开始标记，再输出 {@code
 * lines} 行、每行 {@code lineBytes - 1} 个 {@code x} 加一个换行，最后一行结束 标记。调用方因此可以按字节精确断言「内容完整、没有被截断、也没有多出内容」。
 *
 * <p>用法：{@code <lines> <lineBytes>}；参数不合法时以非零状态退出，绝不输出半个 payload。
 */
public final class LargeOutputFixtureMain {

  /** payload 开始标记（含换行）。 */
  public static final String BEGIN_MARKER = "payload-begin\n";

  /** payload 结束标记（含换行）。 */
  public static final String END_MARKER = "payload-end\n";

  private static final byte FILL = (byte) 'x';

  private LargeOutputFixtureMain() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.exit(2);
      return;
    }
    int lines;
    int lineBytes;
    try {
      lines = Integer.parseInt(args[0]);
      lineBytes = Integer.parseInt(args[1]);
    } catch (NumberFormatException error) {
      System.exit(2);
      return;
    }
    if (lines <= 0 || lineBytes <= 0) {
      System.exit(2);
      return;
    }
    byte[] line = new byte[lineBytes];
    for (int index = 0; index < lineBytes - 1; index++) {
      line[index] = FILL;
    }
    line[lineBytes - 1] = (byte) '\n';
    try (OutputStream out = new BufferedOutputStream(System.out, 1 << 16)) {
      out.write(BEGIN_MARKER.getBytes(StandardCharsets.UTF_8));
      for (int index = 0; index < lines; index++) {
        out.write(line);
      }
      out.write(END_MARKER.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  /** payload 的精确字节数：开始标记 + 每行 {@code lineBytes} + 结束标记。 */
  public static long payloadBytes(int lines, int lineBytes) {
    return BEGIN_MARKER.length() + (long) lines * lineBytes + END_MARKER.length();
  }

  /** 按它自己的形状写出整份 payload；调用方用它做独立比对。 */
  public static byte[] expectedPayload(int lines, int lineBytes) {
    byte[] payload = new byte[(int) payloadBytes(lines, lineBytes)];
    byte[] begin = BEGIN_MARKER.getBytes(StandardCharsets.UTF_8);
    byte[] end = END_MARKER.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(begin, 0, payload, 0, begin.length);
    int offset = begin.length;
    for (int index = 0; index < lines; index++) {
      for (int position = 0; position < lineBytes - 1; position++) {
        payload[offset + position] = FILL;
      }
      payload[offset + lineBytes - 1] = (byte) '\n';
      offset += lineBytes;
    }
    System.arraycopy(end, 0, payload, offset, end.length);
    return payload;
  }
}
