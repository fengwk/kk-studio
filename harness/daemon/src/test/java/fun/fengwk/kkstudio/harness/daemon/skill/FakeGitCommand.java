package fun.fengwk.kkstudio.harness.daemon.skill;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 确定性的 fake git 子进程：作为 argv-only 辅助程序替代宿主 git，使取消语义可被精确观测。
 *
 * <p>调用方式与真实 git 一致（{@code clone}/{@code rev-parse}/{@code checkout} 是 argv[0]），因此被测代码不需要任何测试专用分支。
 * 控制目录经系统属性传入，URL 形如 {@code fake-git://<mode>/<id>}：{@code block} 模式在写入 started/pid 标记后等待 {@code
 * <id>.gate} 文件（测试据此确认“该操作确实正在等待子进程”），{@code instant} 模式直接完成。
 *
 * <p>以 {@code rev-parse} 输出结尾带换行的 commit id，复现真实 git 的输出形状，因此缺少 trim 的解析会在这里显式失败。
 *
 * <p>该辅助程序不接触网络、不携带任何凭据，标记文件只包含进程 id 与 id。
 */
public final class FakeGitCommand {

  /** 控制目录的系统属性名；测试通过 {@code -D} 注入，argv 中不出现测试专用参数。 */
  public static final String CONTROL_DIRECTORY_PROPERTY = "fakeGit.control";

  /** fake git 解析出的固定 commit id。 */
  public static final String COMMIT_ID = "1234567890abcdef1234567890abcdef12345678";

  /** 来源级迟到发布测试使用的两个不同 commit id。 */
  public static final String OLDER_COMMIT_ID = "1".repeat(40);

  public static final String NEWER_COMMIT_ID = "2".repeat(40);

  private FakeGitCommand() {}

  public static void main(String[] args) throws Exception {
    Path control = Path.of(System.getProperty(CONTROL_DIRECTORY_PROPERTY));
    String subcommand = args.length == 0 ? "" : args[0];
    switch (subcommand) {
      case "clone" -> cloneIntoWorkingDirectory(control, args[args.length - 2]);
      case "rev-parse" -> System.out.println(revision(args));
      case "checkout" -> {
        // checkout 的语义已由 clone 完成的内容表达，这里只需成功退出。
      }
      default -> System.exit(2);
    }
    System.exit(0);
  }

  private static String revision(String[] args) {
    String candidate = args[args.length - 1];
    if (candidate.contains("older-ref")) {
      return OLDER_COMMIT_ID;
    }
    if (candidate.contains("newer-ref")) {
      return NEWER_COMMIT_ID;
    }
    return COMMIT_ID;
  }

  /**
   * 模拟 clone：按 URL 模式等待 gate（block）或立即完成，然后在 cwd 下写出一个可被扫描的 skill。
   *
   * <p>标记写入顺序固定为 pid → started →（等待 gate）→ skill 内容 → released，使测试能在不依赖固定 sleep 的前提下确认进度。
   */
  private static void cloneIntoWorkingDirectory(Path control, String url) throws IOException {
    URI uri = URI.create(url);
    String mode = uri.getHost();
    String id = uri.getPath().substring(1);
    Files.writeString(control.resolve(id + ".pid"), Long.toString(ProcessHandle.current().pid()));
    Files.writeString(control.resolve(id + ".started"), "started");
    if ("block".equals(mode)) {
      awaitGate(control.resolve(id + ".gate"));
    }
    Path skill = Files.createDirectories(Path.of("").toAbsolutePath().resolve(id));
    Files.writeString(
        skill.resolve("SKILL.md"),
        "---\nname: " + id + "\ndescription: " + id + " description\n---\n# " + id + "\n");
    Files.writeString(control.resolve(id + ".released"), "released");
  }

  /** 等待 gate 文件出现；只以文件系统事实作为同步，不依赖固定时长。 */
  private static void awaitGate(Path gate) {
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    while (!Files.exists(gate)) {
      if (System.nanoTime() > deadline) {
        System.exit(3);
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        System.exit(4);
      }
    }
  }
}
