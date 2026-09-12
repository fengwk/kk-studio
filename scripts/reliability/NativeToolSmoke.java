package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 在 reliability Daemon 镜像内直接验证 Java coding tools，不经过 Provider。 */
final class NativeToolSmoke {

  private static int assertions;

  private NativeToolSmoke() {}

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory(Path.of("/workspace"), ".native-tool-smoke-");
    ExecutorService executor = Executors.newCachedThreadPool();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    Path workdir = root.toRealPath();
    if (!workdir.isAbsolute()) {
      throw new AssertionError("smoke workdir must be absolute: " + workdir);
    }
    try {
      seed(root);
      CodingToolsConfig config =
          new CodingToolsConfig(
              root,
              CodingToolsConfig.DEFAULT_PREVIEW_MAX_LINES,
              CodingToolsConfig.DEFAULT_PREVIEW_MAX_BYTES,
              "bash",
              new LocalFileResourceStore(root.resolve(".resources")));

      EnvironmentCapabilityResult recursive =
          invoke(
              new FindCapability(config, executor),
              "{\"pattern\":\"**/*.ts\",\"path\":\".\",\"workdir\":\"" + workdir + "\"}");
      check(
          text(recursive).equals("src/a.ts\nsrc/nested/b.ts"),
          "recursive double-star find mismatch: " + text(recursive));

      EnvironmentCapabilityResult slashPattern =
          invoke(
              new FindCapability(config, executor),
              "{\"pattern\":\"src/*.ts\",\"path\":\".\",\"workdir\":\"" + workdir + "\"}");
      check(
          text(slashPattern).equals("src/a.ts"),
          "slash-containing find must remain segment-aware: " + text(slashPattern));

      EnvironmentCapabilityResult escapedClass =
          invoke(
              new FindCapability(config, executor),
              "{\"pattern\":\"[\\\\d].txt\",\"path\":\".\",\"workdir\":\"" + workdir + "\"}");
      check(
          text(escapedClass).equals("d.txt"),
          "escaped glob character-class literal mismatch: " + text(escapedClass));

      EnvironmentCapabilityResult ignored =
          invoke(
              new FindCapability(config, executor),
              "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":\"" + workdir + "\"}");
      check(!text(ignored).contains("ignored.txt"), "root .gitignore file leaked");
      check(!text(ignored).contains("ignored-dir/hidden.ts"), "ignored directory leaked");
      check(!text(ignored).contains(".git/config"), ".git metadata leaked");
      check(text(ignored).contains(".pi/git/.gitignore"), "nested negation was not applied");
      check(!text(ignored).contains(".pi/git/generated.txt"), "nested ignored file leaked");

      EnvironmentCapabilityResult grep =
          invoke(
              new GrepCapability(config, executor),
              "{\"pattern\":\"firstKeptEntryId\",\"path\":\".\",\"include\":\"**/*.ts\",\"workdir\":\""
                  + workdir
                  + "\"}");
      check(
          text(grep).equals("src/a.ts:1:const firstKeptEntryId = 7;"),
          "grep path/line projection mismatch: " + text(grep));

      EnvironmentCapabilityResult multiline =
          invoke(
              new GrepCapability(config, executor),
              "{\"pattern\":\"alpha\\nbeta\",\"path\":\"multi.txt\",\"multiline\":true,\"workdir\":\""
                  + workdir
                  + "\"}");
      check(
          text(multiline).equals("multi.txt:1:alpha\nmulti.txt:2:beta"),
          "multiline grep mismatch: " + text(multiline));

      EnvironmentCapabilityResult invalid =
          invoke(
              new GrepCapability(config, executor),
              "{\"pattern\":\"[\",\"path\":\".\",\"workdir\":\"" + workdir + "\"}");
      check(invalid.error(), "invalid regex must be an error");
      check(text(invalid).contains("Invalid regex"), "invalid regex error must be explicit");

      EnvironmentCapabilityResult binary =
          invoke(
              new GrepCapability(config, executor),
              "{\"pattern\":\"x\",\"path\":\"binary.bin\",\"workdir\":\"" + workdir + "\"}");
      check(binary.error(), "direct binary grep must be an error");
      check(text(binary).contains("binary"), "direct binary grep error must identify binary input");

      EnvironmentCapabilityResult ansi =
          invoke(
              new BashCapability(config, executor, scheduler),
              "{\"command\":\"printf '\\\\033[32mpassed\\\\033[0m\\\\n'\",\"workdir\":\""
                  + workdir
                  + "\"}");
      check(text(ansi).contains("passed"), "ANSI bash output must remain readable");
      check(
          ansi.contents().stream().noneMatch(ResourceResultContent.class::isInstance),
          "small ANSI bash output must not be classified as binary/resource");

      System.out.printf(
          "native-java-tool-smoke=PASS assertions=%d rg_fd_required=false%n", assertions);
    } finally {
      scheduler.shutdownNow();
      executor.shutdownNow();
      deleteRecursively(root);
    }
  }

  private static void seed(Path root) throws Exception {
    write(root, ".gitignore", "ignored.txt\nignored-dir/\n.pi/git/*\n");
    write(root, "src/a.ts", "const firstKeptEntryId = 7;\n");
    write(root, "src/nested/b.ts", "export const nested = true;\n");
    write(root, "src/nested/c.md", "firstKeptEntryId\n");
    write(root, "ignored.txt", "firstKeptEntryId\n");
    write(root, "ignored-dir/hidden.ts", "firstKeptEntryId\n");
    write(root, ".pi/git/.gitignore", "!.gitignore\n");
    write(root, ".pi/git/generated.txt", "firstKeptEntryId\n");
    write(root, ".git/config", "firstKeptEntryId\n");
    write(root, "d.txt", "literal d\n");
    write(root, "1.txt", "digit one\n");
    write(root, "multi.txt", "alpha\nbeta\ngamma\n");
    Files.write(root.resolve("binary.bin"), new byte[] {'x', 0, 'y'});
  }

  private static void write(Path root, String relative, String content) throws Exception {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static EnvironmentCapabilityResult invoke(
      EnvironmentCapability capability, String arguments) throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall(
                "smoke-" + capability.descriptor().id().value(), arguments),
            Duration.ofSeconds(15)),
        listener);
    if (!listener.completed.await(20, TimeUnit.SECONDS)) {
      throw new AssertionError(capability.descriptor().id() + " did not complete");
    }
    if (listener.failure != null) {
      throw new AssertionError(capability.descriptor().id() + " callback failed", listener.failure);
    }
    return listener.result;
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream().map(NativeToolSmoke::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static void check(boolean condition, String message) {
    assertions++;
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static void deleteRecursively(Path root) throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;
    private volatile Throwable failure;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {}

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      failure = error;
      completed.countDown();
    }
  }
}
