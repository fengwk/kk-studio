package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在本次调用声明的 workdir 下按意图创建或完整覆盖单个文本文件。
 *
 * <p>{@code path} 为绝对路径时无需 {@code workdir}；为相对路径时必须提供本次调用的显式绝对 {@code workdir}，否则在执行前拒绝且不回退到
 * cwd、HOME 或任何会话默认目录。workdir 只用于解析路径，不是文件系统沙箱。
 *
 * <p>调用给出的 content 就是写入内容本身：不做换行风格改写，也不会为了沿用既有行尾而变换用户内容。目标已存在且为普通文本文件时，按既有约定无损保留它的 编码与
 * BOM，无法在该编码下无损表示的内容则拒绝写入。目录、设备、FIFO 等非普通文件在任何 I/O 之前拒绝。
 */
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
    Path path = EnvironmentPaths.writable(rawPath);
    String displayPath = EnvironmentPaths.displayPath(path, rawPath);

    ReentrantLock lock = FileMutations.lock(path);
    try {
      // 取消只在提交前生效：一旦提交成立，本方法只返回成功终态。
      requireNotCancelled(execution);

      boolean existed = Files.exists(path);
      Charset charset = StandardCharsets.UTF_8;
      int bomLength = 0;
      if (existed) {
        if (Files.isDirectory(path)) {
          throw new IllegalArgumentException("path is a directory: " + displayPath);
        }
        if (!Files.isRegularFile(path)) {
          throw new IllegalArgumentException("path is not a regular file: " + displayPath);
        }
        TextFileCodec.Decoded existing = TextFileCodec.decode(Files.readAllBytes(path));
        charset = existing.charset();
        bomLength = existing.bomLength();
      } else if (Files.isSymbolicLink(path)) {
        // 悬空符号链接不是待创建的新文件：替换它会静默丢掉链接本身。
        throw new IllegalArgumentException("path is a symbolic link: " + displayPath);
      }

      byte[] encoded = TextFileCodec.encode(content, charset, bomLength);

      requireNotCancelled(execution);
      TextFileCommit.commit(path, encoded);

      return success(
          request.call().id(),
          (existed ? "Overwrote " : "Created ") + displayPath + " successfully.");
    } finally {
      lock.unlock();
    }
  }

  private static void requireNotCancelled(Execution execution) throws InterruptedException {
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
  }
}
