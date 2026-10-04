package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.share.ai.environment.DaemonConfigurationCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * LSP 配置读取、服务器选择、可执行程序解析与项目根发现的契约。
 *
 * <p>项目根规则逐条对齐 pi-base {@code src/lsp/discovery.ts}：git 边界内的最浅 build root 优先，其次是最近的 first-match 标记，
 * 都没有则回退文件父目录；{@code .git} 只是边界，本身不是项目根，且扫描不会越过它去复用上层仓库（嵌套 worktree）的标记。
 */
class LspDiscoveryTest {

  @TempDir Path root;

  /** 意图：扩展名匹配大小写不敏感，未被任何服务器声明的扩展名不匹配。 */
  @Test
  void selectsServerByExtensionCaseInsensitively() {
    LspDiscovery discovery =
        LspDiscovery.of(
            List.of(
                server("java", List.of("java"), List.of(".java")),
                server("ts", List.of("ts-server"), List.of(".ts", ".tsx"))));

    assertEquals(Optional.of("java"), discovery.server(Path.of("/p/App.JAVA")).map(s -> s.id()));
    assertEquals("ts", discovery.server(Path.of("/p/App.tsx")).orElseThrow().id());
    assertTrue(discovery.server(Path.of("/p/readme.md")).isEmpty());
    assertTrue(discovery.server(Path.of("/p/Makefile")).isEmpty());
    assertTrue(discovery.server(root).isEmpty(), "目录名没有扩展名时也不匹配");
  }

  /** 意图：git 边界内的最浅 build root 是项目根；内层模块的 build 标记不改变它。 */
  @Test
  void prefersShallowestRootMarkerInsideGitBoundary() throws IOException {
    Path repo = Files.createDirectories(root.resolve("repo"));
    Files.createDirectories(repo.resolve(".git"));
    Files.writeString(repo.resolve("pom.xml"), "<project/>");
    Path module = Files.createDirectories(repo.resolve("module"));
    Files.writeString(module.resolve("pom.xml"), "<project/>");
    Path file = Files.createDirectories(module.resolve("src")).resolve("App.java");
    Files.writeString(file, "class App {}\n");

    LspServerConfig server =
        new LspServerConfig(
            "java", List.of("java"), List.of(".java"), List.of("pom.xml"), List.of());
    assertEquals(repo, LspDiscovery.of(List.of(server)).workspaceRoot(server, file));
  }

  /** 意图：worktree 的 {@code .git} 文件是硬边界，扫描不得继续到主仓并复用它的 build 标记。 */
  @Test
  void stopsAtWorktreeGitFileBoundary() throws IOException {
    Path main = Files.createDirectories(root.resolve("main"));
    Files.createDirectories(main.resolve(".git"));
    Files.writeString(main.resolve("pom.xml"), "<project/>");
    Path worktree = Files.createDirectories(main.resolve("worktree"));
    Files.writeString(worktree.resolve(".git"), "gitdir: ../.git/worktrees/worktree\n");
    Path module = Files.createDirectories(worktree.resolve("module"));
    Files.writeString(module.resolve("pom.xml"), "<project/>");
    Path file = Files.createDirectories(module.resolve("src")).resolve("App.java");
    Files.writeString(file, "class App {}\n");

    LspServerConfig server =
        new LspServerConfig(
            "java", List.of("java"), List.of(".java"), List.of("pom.xml"), List.of());
    assertEquals(module, LspDiscovery.of(List.of(server)).workspaceRoot(server, file));
  }

  /** 意图：没有 rootMarkers 命中时用最近的 firstMatchMarkers 目录；两者都没有则回退文件父目录。 */
  @Test
  void fallsBackToFirstMatchMarkerThenFileParent() throws IOException {
    Path repo = Files.createDirectories(root.resolve("repo"));
    Files.createDirectories(repo.resolve(".git"));
    Path nested = Files.createDirectories(repo.resolve("packages/web"));
    Files.writeString(nested.resolve("package.json"), "{}");
    Path file = Files.createDirectories(nested.resolve("src")).resolve("App.ts");
    Files.writeString(file, "export const app = 1;\n");

    LspServerConfig firstMatch =
        new LspServerConfig(
            "ts", List.of("java"), List.of(".ts"), List.of(), List.of("package.json"));
    assertEquals(nested, LspDiscovery.of(List.of(firstMatch)).workspaceRoot(firstMatch, file));

    LspServerConfig noMarkers =
        new LspServerConfig("ts", List.of("java"), List.of(".ts"), List.of(), List.of());
    assertEquals(
        nested.resolve("src"), LspDiscovery.of(List.of(noMarkers)).workspaceRoot(noMarkers, file));
  }

  /** 意图：非 git 项目没有边界，行为与没有标记时一致。 */
  @Test
  void nonGitProjectUsesFileParentDirectory() throws IOException {
    Path nested = Files.createDirectories(root.resolve("plain/nested"));
    Path file = Files.writeString(nested.resolve("App.kt"), "val app = 1\n");

    LspServerConfig server = server("kt", List.of("java"), List.of(".kt"));
    assertEquals(nested, LspDiscovery.of(List.of(server)).workspaceRoot(server, file));
  }

  /** 意图：read 侧判定区分三态——未配置、已配置但未安装、可用；判定过程不启动任何进程。 */
  @Test
  void supportRequiresInstalledExecutable() throws IOException {
    Path executable = executable("java-ls");
    Path file = Files.writeString(root.resolve("App.java"), "class App {}\n");
    Path other = Files.writeString(root.resolve("App.py"), "print(1)\n");

    LspDiscovery installed =
        LspDiscovery.of(List.of(server("java", List.of(executable.toString()), List.of(".java"))));
    LspSupport available = installed.support(file);
    assertTrue(available.supported());
    assertTrue(available.available());
    assertEquals(Optional.of("java"), available.language());
    assertFalse(installed.support(other).supported(), "没有服务器负责 .py");

    LspDiscovery missing =
        LspDiscovery.of(
            List.of(server("java", List.of(root.resolve("nope").toString()), List.of(".java"))));
    LspSupport notInstalled = missing.support(file);
    assertTrue(notInstalled.supported());
    assertFalse(notInstalled.available(), "配置命中但可执行程序不存在");

    assertFalse(LspDiscovery.empty().support(file).supported());
    assertFalse(LspSupport.unsupported().available());
    assertTrue(LspSupport.unsupported().language().isEmpty());

    IllegalStateException noServer =
        assertThrows(IllegalStateException.class, () -> missing.requireServer(other));
    assertTrue(noServer.getMessage().contains("No LSP server configured"), noServer.getMessage());
    IllegalStateException notOnDisk =
        assertThrows(
            IllegalStateException.class,
            () ->
                missing.requireExecutable(
                    server("java", List.of("no-such-binary"), List.of(".java"))));
    assertTrue(notOnDisk.getMessage().contains("is not installed"), notOnDisk.getMessage());
  }

  /** 意图：绝对路径与 {@code ~}/{@code $HOME} 前缀都被解析；相对命令不是可执行程序。 */
  @Test
  void resolvesAbsoluteAndHomeRelativeCommands() throws IOException {
    Path home = Files.createDirectories(root.resolve("home"));
    Path homeExecutable = executable(Files.createDirectories(home.resolve("bin")), "home-ls");
    String previousHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      List<LspServerConfig> servers =
          List.of(
              server("a", List.of(homeExecutable.toString()), List.of(".a")),
              server("b", List.of("~/bin/home-ls"), List.of(".b")),
              server("c", List.of("$HOME/bin/home-ls"), List.of(".c")),
              server("d", List.of("relative/tool"), List.of(".d")));
      LspDiscovery discovery = LspDiscovery.of(servers);

      assertEquals(homeExecutable.toString(), discovery.executable(servers.get(0)).orElseThrow());
      assertEquals(homeExecutable.toString(), discovery.executable(servers.get(1)).orElseThrow());
      assertEquals(homeExecutable.toString(), discovery.executable(servers.get(2)).orElseThrow());
      assertTrue(discovery.executable(servers.get(3)).isEmpty(), "相对路径不是可执行程序");
    } finally {
      System.setProperty("user.home", previousHome);
    }
  }

  /** 意图：共享结构化配置转换为运行时快照，声明顺序与规范化扩展名保持不变。 */
  @Test
  void readsServersFromJsonConfiguration() throws IOException {
    var config =
        DaemonConfigurationCodec.parseLsp(
            new ObjectMapper()
                .readTree(
                    """
            {"servers":{
              "java":{"command":["/usr/bin/java"],"extensions":[".java"],"rootMarkers":["pom.xml"]},
              "ts":{"command":["ts-server"],"extensions":[".TS",".tsx"],"firstMatchMarkers":["package.json"]}
            }}
            """));

    LspDiscovery discovery = LspDiscovery.fromConfiguration(config);
    assertEquals(
        List.of("java", "ts"), discovery.servers().stream().map(LspServerConfig::id).toList());
    assertEquals(List.of(".java"), discovery.servers().getFirst().extensions());
    assertEquals(List.of("pom.xml"), discovery.servers().getFirst().rootMarkers());
    assertEquals(List.of("package.json"), discovery.servers().get(1).firstMatchMarkers());
    assertEquals(List.of(".ts", ".tsx"), discovery.servers().get(1).extensions());
    assertEquals("ts", discovery.server(Path.of("/p/App.ts")).orElseThrow().id());
    config.getServers().get("ts").setCommand(List.of("changed"));
    config.getServers().clear();
    assertEquals(List.of("ts-server"), discovery.servers().get(1).command());
    assertEquals(2, discovery.servers().size(), "运行时快照不受共享 JavaBean 后续修改影响");
  }

  /** 外部 JSON 校验由共享 codec 测试覆盖；内部发现器仍拒绝重复运行时 ID。 */
  @Test
  void rejectsDuplicateRuntimeServerIds() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LspDiscovery.of(
                    List.of(
                        server("a", List.of("x"), List.of(".a")),
                        server("a", List.of("x"), List.of(".b")))));
    assertTrue(error.getMessage().contains("duplicate lsp server id"));
  }

  private static LspServerConfig server(String id, List<String> command, List<String> extensions) {
    return new LspServerConfig(id, command, extensions, List.of(), List.of());
  }

  private Path executable(String name) throws IOException {
    return executable(root, name);
  }

  private static Path executable(Path directory, String name) throws IOException {
    Path executable = Files.writeString(directory.resolve(name), "#!/bin/sh\nexit 0\n");
    if (!executable.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark executable: " + executable);
    }
    return executable;
  }
}
