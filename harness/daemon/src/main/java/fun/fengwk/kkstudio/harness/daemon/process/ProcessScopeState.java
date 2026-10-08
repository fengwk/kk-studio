package fun.fengwk.kkstudio.harness.daemon.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 执行范围父进程与 helper 之间唯一的握手面：调用私有状态目录里的这几个文件。
 *
 * <p>发布一律「先写同目录临时文件再原子改名」，读者要么看不到文件，要么看到完整内容；读取失败按「还没有发布」处理，由调用方的 启动预算与 helper
 * 存活判定兜住。目录与其中所有文件都在调用结束时删除。
 */
final class ProcessScopeState {

  /** helper 发布的范围渠道：POSIX 是进程组 id，Windows 是首个进程 pid。 */
  static final String SCOPE_FILE = "scope";

  /** 父进程在确认范围之后放行用户命令的许可文件：没有它，helper 不会启动任何进程。 */
  static final String PERMIT_FILE = "permit";

  /** helper 发布的用户命令自然退出码。 */
  static final String EXIT_FILE = "exit";

  /** helper 发布的失败原因。 */
  static final String ERROR_FILE = "error";

  /** helper 发布的整组收敛事实（Windows Job 已确认没有活动进程）。 */
  static final String CLEANUP_FILE = "cleanup";

  /** helper 自身的诊断输出；绝不进入用户命令的输出流。 */
  static final String DIAGNOSTICS_FILE = "diagnostics";

  private ProcessScopeState() {}

  /** 原子发布状态文件；失败必须让调用方显式失败，绝不留下半个文件。 */
  static void publish(Path stateDir, String name, String content) {
    Path target = stateDir.resolve(name);
    try {
      Path temporary = stateDir.resolve(name + ".tmp");
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException error) {
      throw new IllegalStateException(
          "cannot publish process scope state " + target + ": " + error.getMessage(), error);
    }
  }

  /** 尽力发布：只用于失败报告本身，报告失败时只能放弃。 */
  static void publishQuietly(Path stateDir, String name, String content) {
    try {
      publish(stateDir, name, content);
    } catch (RuntimeException ignored) {
      // 失败原因不可发布时不再尝试第二种发布方式。
    }
  }

  /** 读取状态文件；不存在或不可读都按「还没有发布」处理。 */
  static String read(Path stateDir, String name) {
    try {
      Path file = stateDir.resolve(name);
      return Files.isRegularFile(file) ? Files.readString(file).trim() : null;
    } catch (IOException error) {
      return null;
    }
  }

  /** 删除调用私有的状态目录；清理失败不影响调用结果。 */
  static void deleteQuietly(Path directory) {
    if (directory == null || !Files.exists(directory)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // 单条残留的清理失败不能影响调用收尾；目录本身仍会被尝试删除。
        }
      }
    } catch (IOException ignored) {
      // 状态目录只承载调用私有的握手文件，清理失败不改变调用结果。
    }
  }
}
