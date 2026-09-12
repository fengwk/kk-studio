package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Path path =
        EnvironmentPaths.writable(rawPath, EnvironmentPaths.workdir(string(args, "workdir")));
    ReentrantLock lock = FileMutations.lock(path);
    try {
      if (execution.isCancelled()) {
        throw new InterruptedException();
      }
      boolean existed = Files.exists(path);
      TextFileCodec.Decoded existing =
          existed
              ? TextFileCodec.decode(Files.readAllBytes(path))
              : new TextFileCodec.Decoded("", StandardCharsets.UTF_8, 0);
      Path parent = Objects.requireNonNull(path.getParent(), "writable path must have a parent");
      Files.createDirectories(parent);
      if (execution.isCancelled()) {
        throw new InterruptedException();
      }
      Files.write(path, TextFileCodec.encode(content, existing.charset(), existing.bomLength()));
      return success(
          request.call().id(), (existed ? "Overwrote " : "Created ") + rawPath + " successfully.");
    } finally {
      lock.unlock();
    }
  }
}
