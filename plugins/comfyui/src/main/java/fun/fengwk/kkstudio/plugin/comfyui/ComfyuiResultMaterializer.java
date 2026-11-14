package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 远端执行结果的解释与物化：按冻结 selector 解释 history 输出、轮询等待，再逐个下载并按冻结顺序物化。
 *
 * <p>每个下载流都有独立 deadline，且都由 try-with-resources 释放；未声明的节点与字段一律不取。
 */
final class ComfyuiResultMaterializer {

  private final ComfyuiWorkflow workflow;
  private final ComfyuiSettings settings;
  private final StandardComfyuiClient client;

  ComfyuiResultMaterializer(
      ComfyuiWorkflow workflow, ComfyuiSettings settings, StandardComfyuiClient client) {
    this.workflow = workflow;
    this.settings = settings;
    this.client = client;
  }

  /** 轮询 history 直到 SUCCESS；PENDING 在整体 deadline 内按 poll interval 重试。 */
  List<H3OutputDescriptor> awaitOutputs(CanvasFunctionExecutionContext context, String promptId) {
    long deadline = System.nanoTime() + settings.maxWaitMillis() * 1_000_000L;
    Duration pollInterval = Duration.ofMillis(settings.pollIntervalMillis());
    while (true) {
      ComfyuiRunGuard.ensureRunning(context);
      H3ComfyHistory history = client.history(promptId);
      switch (history.status()) {
        case SUCCESS -> {
          return extractOutputs(history.outputs());
        }
        case ERROR -> throw new IllegalStateException(
            "ComfyUI execution failed: " + history.error());
        case PENDING -> {
          if (System.nanoTime() - deadline >= 0L) {
            throw new IllegalStateException(
                "ComfyUI execution timed out after " + settings.maxWaitMillis() + "ms");
          }
          sleep(pollInterval);
        }
      }
    }
  }

  /** 按冻结顺序逐个下载并物化。 */
  void materialize(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      List<H3OutputDescriptor> outputs) {
    for (int index = 0; index < outputs.size(); index++) {
      ComfyuiRunGuard.ensureRunning(context);
      try (H3ComfyDownload download = client.download(outputs.get(index))) {
        context.materializeOutput(run.output(index), download.content());
      } catch (IOException error) {
        throw new IllegalStateException("cannot close ComfyUI output stream", error);
      }
    }
  }

  /** 按冻结 selector 顺序解释远端协议输出。 */
  private List<H3OutputDescriptor> extractOutputs(ObjectNode outputs) {
    List<H3OutputDescriptor> files = new ArrayList<>(workflow.outputs().size());
    for (ComfyuiWorkflow.OutputSelector selector : workflow.outputs()) {
      String path = "history outputs." + selector.nodeId();
      ObjectNode node = object(outputs.get(selector.nodeId()), path);
      ArrayNode array = array(node.get(selector.field().jsonName()), path);
      if (selector.index() >= array.size()) {
        throw new IllegalArgumentException("ComfyUI history output selector is out of range");
      }
      ObjectNode item = object(array.get(selector.index()), path);
      files.add(
          new H3OutputDescriptor(
              text(item, "filename"), string(item, "subfolder"), text(item, "type")));
    }
    return files;
  }

  private static ObjectNode object(JsonNode value, String path) {
    if (value == null || !(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(path + " must be a JSON object");
    }
    return object;
  }

  private static ArrayNode array(JsonNode value, String path) {
    if (value == null || !(value instanceof ArrayNode node)) {
      throw new IllegalArgumentException(path + " must be a JSON array");
    }
    return node;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("field " + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String string(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      return "";
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("field " + field + " must be a string");
    }
    return value.textValue();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ComfyUI polling was interrupted", interrupted);
    }
  }
}
