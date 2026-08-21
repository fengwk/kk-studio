package fun.fengwk.kkstudio.core.studio.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 无 shell 的有界媒体子进程执行器。 */
final class MediaProcessRunner {

  private static final int MAX_ERROR_TEXT_BYTES = 16 * 1024;

  MediaProcessRunner() {}

  void run(List<String> command, Path stdout, Path stderr, Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("processTimeout must be positive");
    }
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
      builder.redirectOutput(stdout.toFile());
      builder.redirectError(stderr.toFile());
      process = builder.start();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start media process: " + command.get(0), e);
    }

    boolean completed;
    try {
      completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IllegalStateException("Media process interrupted: " + command.get(0), e);
    }
    if (!completed) {
      process.destroyForcibly();
      try {
        process.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("Media process timed out: " + command.get(0));
    }
    if (process.exitValue() != 0) {
      throw new IllegalArgumentException(
          "Media process failed ("
              + command.get(0)
              + ", exit="
              + process.exitValue()
              + "): "
              + readError(stderr));
    }
  }

  private String readError(Path stderr) {
    try (var input = Files.newInputStream(stderr)) {
      byte[] bytes = input.readNBytes(MAX_ERROR_TEXT_BYTES);
      return new String(bytes, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "unable to read process error output";
    }
  }
}
