package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PosixProcessGroup#createSession()} 的独立进程夹具：session/进程组语义只能在真正的进程里成立。
 *
 * <p>第一次调用必须成功（新建 session 与进程组），第二次必须在同一进程里显式失败——那时它已经是进程组 leader，内核会以 EPERM
 * 拒绝。把结论写进文件而不是标准输出，父进程据此得到「进程内可见的事实」。
 */
public final class PosixProcessGroupSessionFixture {

  private PosixProcessGroupSessionFixture() {}

  public static void main(String[] args) throws IOException {
    List<String> lines = new ArrayList<>();
    try {
      PosixProcessGroup.createSession();
      lines.add("first=ok");
    } catch (IllegalStateException error) {
      lines.add("first=failed");
    }
    lines.add(
        PosixProcessGroup.currentGroup() == ProcessHandle.current().pid()
            ? "group=leader"
            : "group=other");
    try {
      PosixProcessGroup.createSession();
      lines.add("second=ok");
    } catch (IllegalStateException error) {
      lines.add("second=failed");
    }
    Files.write(Path.of(args[0]), lines, StandardCharsets.UTF_8);
    System.exit(0);
  }
}
