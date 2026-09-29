package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把父 JVM 已经挂上的 JaCoCo 代理转发给测试自行启动的辅助进程。
 *
 * <p>{@code ProcessScope} 在正常调用里自己完成这件事（helper 的数据写进独立的 jacoco-helper 目录）；但有些用例必须直接 启动辅助进程而不经过
 * {@code ProcessScope}（helper 入口的参数校验与启动预算、session 语义的夹具），因此由测试自己转发。
 *
 * <p>数据必须落到**独立文件**：父 JVM 的代理在启动时读入定位文件、退出时整份写回，直接把子进程数据写进同一个定位文件会在 父 JVM
 * 退出时被覆盖掉，覆盖率会凭空少一段。不挂代理时返回空列表，测试照常运行。
 */
final class TestCoverageAgentArguments {

  private static final Pattern DESTFILE = Pattern.compile("destfile=([^,]+)");

  private TestCoverageAgentArguments() {}

  static List<String> forwarded() {
    return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
        .findFirst()
        .map(TestCoverageAgentArguments::withSeparateDestination)
        .map(List::of)
        .orElse(List.of());
  }

  private static String withSeparateDestination(String agent) {
    Matcher matcher = DESTFILE.matcher(agent);
    if (!matcher.find()) {
      return agent;
    }
    Path destination = Path.of(matcher.group(1)).resolveSibling("jacoco-helper");
    try {
      Files.createDirectories(destination);
    } catch (IOException error) {
      return agent;
    }
    String isolated = destination.resolve("direct-" + UUID.randomUUID() + ".exec").toString();
    return agent.substring(0, matcher.start(1)) + isolated + agent.substring(matcher.end(1));
  }
}
