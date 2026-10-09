package fun.fengwk.kkstudio.harness.daemon.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 执行范围父进程与 helper 之间唯一的握手面：调用私有状态目录里的这几个文件。
 *
 * <p>发布一律「先写同目录临时文件再原子改名」，读者要么看不到文件，要么看到完整内容；读取失败按「还没有发布」处理，由调用方的 启动预算与 helper
 * 存活判定兜住。目录与其中所有文件都在调用结束时删除。
 */
final class ProcessScopeState {

  /** helper 发布的范围渠道：POSIX 是会话 id，Windows 是首个进程 pid。 */
  static final String SCOPE_FILE = "scope";

  /** 父进程在确认范围之后放行用户命令的许可文件：没有它，helper 不会启动任何进程。 */
  static final String PERMIT_FILE = "permit";

  /** helper 发布的用户命令自然退出码。 */
  static final String EXIT_FILE = "exit";

  /** helper 发布的失败原因。 */
  static final String ERROR_FILE = "error";

  /** helper 发布的收敛事实：POSIX 会话或 Windows Job 已确认没有活动成员。 */
  static final String CLEANUP_FILE = "cleanup";

  /** helper 自身启动规格（workdir 与 argv）；读取后立即删除，绝不进入命令行或诊断。 */
  static final String LAUNCH_FILE = "launch";

  /** helper 自身的诊断输出；绝不进入用户命令的输出流。 */
  static final String DIAGNOSTICS_FILE = "diagnostics";

  /** 启动规格的 JSON 编解码器：字段固定为 workdir 与 command，不含其它字段。 */
  private static final ObjectMapper LAUNCH_MAPPER = new ObjectMapper();

  private ProcessScopeState() {}

  /**
   * 原子发布 helper 启动规格：单一 JSON 文档（{@code workdir} 字符串与 {@code command} 字符串数组）。
   *
   * <p>用 JSON 而不是分隔符编码：分隔符会被命令参数里的换行、引号、反斜杠或 Unicode 破坏，而 JSON 对这些字符无损，也不会产生
   * 「空参数被吃掉」或「参数被拆开」的歧义。规格只承载启动配置（workdir 与 argv），不含任何运行字节、输入或屏幕内容；helper 读取后 立即删除，因此命令行与 pty4j
   * 线程名不会带上启动参数。
   *
   * <p>argv 中出现 {@code NUL} 时显式拒绝：{@code execve} 无法承载它，静默改写会让命令与调用方看到的不一致。
   */
  static void publishLaunch(Path stateDir, Path workdir, List<String> command) {
    String directory = workdir.toString();
    rejectNul(directory);
    for (String argument : command) {
      rejectNul(argument);
    }
    byte[] payload;
    try {
      payload = LAUNCH_MAPPER.writeValueAsBytes(new LaunchSpec(directory, List.copyOf(command)));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode the helper launch spec", error);
    }
    Path target = stateDir.resolve(LAUNCH_FILE);
    Path temporary = stateDir.resolve(LAUNCH_FILE + ".tmp");
    try {
      Files.write(temporary, payload);
      restrictToOwner(temporary);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException error) {
      throw new IllegalStateException(
          "cannot publish the helper launch spec: " + error.getMessage(), error);
    }
  }

  /**
   * 把启动规格收紧为属主可读写；文件系统支持 POSIX 权限时失败必须显式失败，绝不静默继续。
   *
   * <p>启动规格属于敏感控制文件：它承载用户命令的 argv，放宽可读性等于把它暴露给同机其它用户。
   */
  private static void restrictToOwner(Path file) {
    try {
      if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      }
    } catch (IOException error) {
      throw new IllegalStateException(
          "cannot restrict the helper launch spec to its owner: " + error.getMessage(), error);
    }
  }

  private static void rejectNul(String value) {
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("the helper launch spec must not contain a NUL character");
    }
  }

  /** 读取并立即删除启动规格；不存在时返回 {@code null}；不可解析时显式失败且不回显载荷。 */
  static LaunchSpec readLaunch(Path stateDir) {
    Path file = stateDir.resolve(LAUNCH_FILE);
    try {
      if (!Files.isRegularFile(file)) {
        return null;
      }
      byte[] payload = Files.readAllBytes(file);
      Files.deleteIfExists(file);
      LaunchSpec spec = LAUNCH_MAPPER.readValue(payload, LaunchSpec.class);
      if (spec.workdir() == null || spec.command() == null) {
        throw new IllegalStateException("the helper launch spec is incomplete");
      }
      return spec;
    } catch (IOException error) {
      // 不把载荷或解析细节写进失败原因：启动规格里是用户命令的 argv。
      throw new IllegalStateException("the helper launch spec is unreadable", error);
    }
  }

  /** helper 启动规格：命令的 workdir 与 argv。字段固定；JSON 里无法识别的内容不会进入这里。 */
  record LaunchSpec(String workdir, List<String> command) {}

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
