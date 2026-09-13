package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/** 在本次调用显式 workdir 下按意图创建或替换单个文本文件。 */
public final class WriteCapability extends AbstractCodingCapability {

  public WriteCapability(CodingToolsConfig config, ExecutorService executor) {
    super(
        config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_WRITE));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    String content = string(args, "content");
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.writable(rawPath, workdir);
    String displayPath = EnvironmentPaths.displayPath(path, workdir, rawPath);

    ReentrantLock lock = FileMutations.lock(path);
    try {
      if (execution.isCancelled()) {
        throw new InterruptedException();
      }

      boolean existed = Files.exists(path);
      if (existed && Files.isDirectory(path)) {
        throw new IllegalArgumentException("path is a directory: " + displayPath);
      }

      TextFileCodec.Decoded existing = null;
      if (existed) {
        byte[] existingBytes = Files.readAllBytes(path);
        existing = TextFileCodec.decode(existingBytes);
      }

      String adaptedContent = content;
      var targetCharset = StandardCharsets.UTF_8;
      int targetBomLength = 0;

      if (existing != null) {
        targetCharset = existing.charset();
        targetBomLength = existing.bomLength();
        String lineEnding = detectLineEnding(existing.text());
        if ("\r\n".equals(lineEnding)) {
          adaptedContent = content.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        } else if ("\r".equals(lineEnding)) {
          adaptedContent = content.replace("\r\n", "\n").replace('\r', '\n').replace('\n', '\r');
        }
      }

      byte[] encoded = TextFileCodec.encode(adaptedContent, targetCharset, targetBomLength);

      Path parent = Objects.requireNonNull(path.getParent(), "writable path must have a parent");
      Files.createDirectories(parent);

      if (execution.isCancelled()) {
        throw new InterruptedException();
      }

      Path tempFile = Files.createTempFile(parent, ".kk-write-", ".tmp");
      try {
        Files.write(tempFile, encoded);
        try {
          Files.move(
              tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tempFile);
      }

      if (!Files.isRegularFile(path)) {
        throw new IllegalStateException("target path is not a regular file: " + displayPath);
      }

      return success(
          request.call().id(),
          (existed ? "Overwrote " : "Created ") + displayPath + " successfully.");
    } finally {
      lock.unlock();
    }
  }

  static String detectLineEnding(String text) {
    if (text == null) {
      return "\n";
    }
    boolean hasCrlf = text.contains("\r\n");
    String withoutCrlf = text.replace("\r\n", "");
    boolean hasCr = withoutCrlf.contains("\r");
    boolean hasLf = withoutCrlf.contains("\n");
    int styles = (hasCrlf ? 1 : 0) + (hasCr ? 1 : 0) + (hasLf ? 1 : 0);
    if (styles > 1) {
      return "mixed";
    }
    if (hasCrlf) {
      return "\r\n";
    }
    if (hasCr) {
      return "\r";
    }
    return "\n";
  }
}
