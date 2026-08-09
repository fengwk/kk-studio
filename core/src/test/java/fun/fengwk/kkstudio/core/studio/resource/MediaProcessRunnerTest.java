package fun.fengwk.kkstudio.core.studio.resource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class MediaProcessRunnerTest {

  @TempDir Path tempDir;

  /** 真实子进程超时后必须被强制终止，不能无限阻塞 finalize worker。 */
  @Test
  void timesOutLongRunningProcess() throws Exception {
    Path script = writeScript("slow", "sleep 5\n");
    MediaProcessRunner runner = new MediaProcessRunner(Duration.ofMillis(100));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.run(
                    List.of(script.toString()),
                    tempDir.resolve("stdout"),
                    tempDir.resolve("stderr")));
    assertTrue(error.getMessage().contains("timed out"));
  }

  /** 非零退出必须携带有限错误文本并作为媒体参数错误返回。 */
  @Test
  void rejectsNonZeroExit() throws Exception {
    Path script = writeScript("fail", "echo expected-failure >&2\nexit 9\n");
    MediaProcessRunner runner = new MediaProcessRunner(Duration.ofSeconds(1));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runner.run(
                    List.of(script.toString()),
                    tempDir.resolve("stdout"),
                    tempDir.resolve("stderr")));
    assertTrue(error.getMessage().contains("expected-failure"));
  }

  private Path writeScript(String name, String body) throws Exception {
    Path script = tempDir.resolve(name);
    Files.writeString(script, "#!/bin/sh\n" + body);
    assertTrue(script.toFile().setExecutable(true));
    return script;
  }
}
