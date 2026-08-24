package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/** 在 environment root 下按意图创建或替换单个文本文件。 */
public final class WriteTool extends AbstractCodingTool {

  public WriteTool(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentToolCatalog.require("write"));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    String content = string(args, "content");
    Path path =
        boundary.writable(
            rawPath, boundary.workdir(optionalString(args, "workdir"), request.workdir()));
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
