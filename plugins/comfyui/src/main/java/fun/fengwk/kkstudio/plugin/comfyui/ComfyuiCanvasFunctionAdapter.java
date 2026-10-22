package fun.fengwk.kkstudio.plugin.comfyui;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonText;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 单个冻结工作流的 Canvas Function adapter：{@code DIRECT} 直接应用已声明绑定，{@code H3} 保留 MiniMax-H3 Ref2VA
 * 的增强/帧长语义。
 *
 * <p>adapter 在装配时冻结配置修订产出的不可变 {@link ComfyuiWorkflow}，因此热更新不会改变已冻结 Run 的解释：图、参数/媒体绑定与输出 selector
 * 都取自 同一份快照。引用媒体通过 Prompt 媒体端口在接受事务外统一暂存，由宿主在接受事务内物化为 durable RESOURCE；因停止、失败或回滚未消费的暂存交由 Storage
 * maintenance 回收。
 */
@Slf4j
public final class ComfyuiCanvasFunctionAdapter implements CanvasFunctionAdapter {

  static final String INITIALIZED = "H3_INITIALIZED";
  static final String PROMPT_SUBMITTING = "H3_PROMPT_SUBMITTING";
  static final String PROMPT_WAITING = "H3_PROMPT_WAITING";
  static final String PROMPT_READY = "H3_PROMPT_READY";
  static final String COMFY_UPLOADING = "H3_COMFY_UPLOADING";
  static final String COMFY_SUBMITTING = "H3_COMFY_SUBMITTING";
  static final String COMFY_WAITING = "H3_COMFY_WAITING";
  static final String COMFY_READY = "H3_COMFY_READY";
  static final String COMPLETE = "H3_COMPLETE";

  private final ComfyuiSettings settings;
  private final ComfyuiWorkflow workflow;
  private final CanvasFunctionDefinition definition;
  private final StandardComfyuiClient client;
  private final H3MediaPreflight mediaPreflight;
  private final H3PromptRequestBuilder promptBuilder;
  private final String unknownSubmission;
  private final PluginPromptTasks promptTasks;
  private final PluginPromptResources promptResources;

  ComfyuiCanvasFunctionAdapter(
      ComfyuiSettings settings,
      ComfyuiWorkflow workflow,
      StandardComfyuiClient client,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      PluginPromptTasks promptTasks,
      PluginPromptResources promptResources) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.workflow = Objects.requireNonNull(workflow, "workflow");
    this.definition = workflow.definition();
    this.client = Objects.requireNonNull(client, "client");
    this.mediaPreflight = mediaPreflight;
    this.promptBuilder = promptBuilder;
    this.promptTasks = promptTasks;
    this.promptResources = promptResources;
    // 未知结果必须能被定位到具体工作流：同一客户端并发承载多个冻结工作流。
    this.unknownSubmission =
        "workflow "
            + workflow.id()
            + " ComfyUI submission outcome unknown; automatic resubmission is forbidden";
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3
        && (mediaPreflight == null
            || promptBuilder == null
            || promptTasks == null
            || promptResources == null)) {
      throw new IllegalStateException("H3 workflows require the Prompt task and media ports");
    }
  }

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return List.of(definition);
  }

  @Override
  public boolean enabled() {
    return settings.enabled();
  }

  @Override
  public String unavailableReason() {
    return settings.enabled() ? null : "ComfyUI is disabled";
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    requireFunction(run);
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3) {
      String prompt = extractText(run.args(), "prompt");
      if (prompt == null || prompt.isBlank()) {
        throw new IllegalArgumentException("H3 prompt must not be blank");
      }
      mediaPreflight.validate(run.manifest());
      return;
    }
    // DIRECT：已知的参数错误必须在任何上传/Prompt/远端提交之前暴露，因此这里只做纯值解析（显式值或 schema 默认）。
    for (ComfyuiWorkflow.ParameterBinding binding : workflow.parameterBindings()) {
      argument(run.args(), binding.argument());
    }
    // DIRECT 媒体绑定按 manifest 顺序固定索引：绑定的数量必须正好覆盖全部 frozen 引用（0 引用合法）。
    if (run.manifest().size() != workflow.mediaBindings().size()) {
      throw new IllegalArgumentException(
          "workflow "
              + workflow.id()
              + " requires exactly "
              + workflow.mediaBindings().size()
              + " media references");
    }
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    requireFunction(run);
    preflight(run);
    H3AdapterState state = H3AdapterState.decode(run.adapterState());

    if (PROMPT_SUBMITTING.equals(run.stage()) && state.harnessThreadId() == null) {
      throw new CanvasFunctionUnknownException(
          "H3 Prompt Agent submission outcome unknown; automatic resubmission is forbidden");
    }
    if (COMFY_SUBMITTING.equals(run.stage()) && state.promptId() == null) {
      throw new CanvasFunctionUnknownException(unknownSubmission);
    }
    if (state.promptId() != null) {
      return;
    }

    H3ReferenceManifest manifest = H3ReferenceManifest.from(run.manifest());
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3) {
      state = enhancePrompt(context, run, manifest, state);
    }
    checkpoint(context, COMFY_UPLOADING, state);
    state = uploadManifest(context, run, manifest, state);

    ObjectNode workflowJson = buildWorkflow(run, manifest, state);
    checkpoint(context, COMFY_SUBMITTING, state);
    String promptId;
    try {
      promptId = client.submit(workflowJson, clientId(run));
    } catch (CanvasFunctionUnknownException unknown) {
      throw unknown;
    } catch (RuntimeException failure) {
      // 只声明结果未知，不回显可能的远端 URI/凭据/Cause。
      throw new CanvasFunctionUnknownException(unknownSubmission);
    }
    checkpoint(context, COMFY_WAITING, state.withPromptId(promptId));
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    requireFunction(run);
    preflight(run);
    H3AdapterState state = H3AdapterState.decode(run.adapterState());
    String stage = run.stage();

    if (COMPLETE.equals(stage)) {
      return run.outputResourceIds();
    }
    if (state.promptId() == null) {
      throw new CanvasFunctionUnknownException(
          "ComfyUI promptId is missing; automatic resubmission is forbidden");
    }
    if (state.outputs() == null) {
      List<H3OutputDescriptor> outputs =
          awaitOutputs(context, requireText(state.promptId(), "promptId"));
      state = state.withOutputs(outputs);
      checkpoint(context, COMFY_READY, state);
      stage = COMFY_READY;
    }
    if (state.outputs() != null && !COMPLETE.equals(stage)) {
      materialize(context, run, state.outputs());
      checkpoint(context, COMPLETE, state);
    }
    return run.outputResourceIds();
  }

  @Override
  public void cancel(CanvasFunctionFrozenRun run) {
    H3AdapterState state;
    try {
      state = H3AdapterState.decode(run.adapterState());
    } catch (RuntimeException error) {
      logCancelFailure(run, error);
      return;
    }
    if (state.harnessThreadId() != null && promptTasks != null) {
      try {
        promptTasks.stop(state.harnessThreadId());
      } catch (RuntimeException error) {
        logCancelFailure(run, error);
      }
    }
    if (state.promptId() != null) {
      try {
        client.cancelPending(state.promptId());
      } catch (RuntimeException error) {
        logCancelFailure(run, error);
      }
    }
  }

  /** H3 提示词增强：seed 初始化、Prompt Agent 提交与等待；提交边界之后的失败一律是 UNKNOWN，绝不自动重提。 */
  private H3AdapterState enhancePrompt(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest,
      H3AdapterState state) {
    if (state.seed() == null) {
      state = state.withSeed(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
      checkpoint(context, INITIALIZED, state);
    }
    if (state.enhancedPrompt() != null) {
      return state;
    }
    if (state.harnessThreadId() == null) {
      checkpoint(context, PROMPT_SUBMITTING, state);
      // 提交前的确定性输入校验与准备：失败必须保持 FAILED，绝不进入 UNKNOWN 保守窗口。
      String promptAgentName = requireText(settings.promptAgentName(), "promptAgentName");
      String systemPrompt = promptBuilder.systemPrompt();
      List<PluginPromptContent> contents = prepareContents(context, run, manifest);
      UUID taskId;
      try {
        taskId = promptTasks.submit(promptAgentName, systemPrompt, contents);
      } catch (CanvasFunctionUnknownException unknown) {
        throw unknown;
      } catch (RuntimeException failure) {
        // 只声明结果未知，不回显可能的远端 URI/凭据/Cause。
        throw new CanvasFunctionUnknownException("H3 Prompt Agent submission outcome unknown");
      }
      state = state.withHarnessThreadId(taskId);
      checkpoint(context, PROMPT_WAITING, state);
    }
    UUID taskId = Objects.requireNonNull(state.harnessThreadId(), "harnessThreadId");
    String enhancedPrompt =
        promptTasks.await(
            taskId, Duration.ofMillis(settings.promptMaxWaitMillis()), context::isRunning);
    state = state.withEnhancedPrompt(enhancedPrompt);
    checkpoint(context, PROMPT_READY, state);
    return state;
  }

  /** 逐个上传尚未上传的 frozen 引用；每个上传产物在产生后立即 checkpoint，断点恢复只补未完成的部分。 */
  private H3AdapterState uploadManifest(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest,
      H3AdapterState state) {
    for (H3ReferenceManifest.Item item : manifest.items()) {
      UUID resourceId = item.reference().resourceId();
      if (state.uploads().containsKey(resourceId)) {
        continue;
      }
      ensureRunning(context);
      try (CanvasFunctionResourceStream original = context.openOriginal(item.reference())) {
        H3UploadedFile uploaded =
            client.upload(
                resourceId + trustedExtension(item.reference()),
                item.reference().mediaType(),
                original.size(),
                original.content(),
                run.canvasId());
        state = state.withUpload(resourceId, uploaded);
        checkpoint(context, COMFY_UPLOADING, state);
      } catch (IOException error) {
        throw new IllegalStateException("cannot close frozen Resource stream", error);
      }
    }
    return state;
  }

  /** 冻结图的一次性副本：DIRECT 按绑定注入参数与媒体，H3 由预设构造器注入增强提示词与动态媒体节点。 */
  private ObjectNode buildWorkflow(
      CanvasFunctionFrozenRun run, H3ReferenceManifest manifest, H3AdapterState state) {
    ObjectNode graph = workflow.graph();
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3) {
      ComfyuiWorkflow.OutputSelector selector = workflow.outputs().get(0);
      return H3WorkflowBuilder.build(
          graph,
          selector.nodeId(),
          requireText(state.enhancedPrompt(), "enhancedPrompt"),
          extractText(run.args(), "ratio", "16:9"),
          extractInt(run.args(), "duration", 5),
          requireNonnegative(state.seed(), "seed"),
          run.output(0).resourceId(),
          manifest,
          state.uploads());
    }
    for (ComfyuiWorkflow.ParameterBinding binding : workflow.parameterBindings()) {
      ComfyuiGraph.write(
          graph,
          binding.nodeId(),
          binding.input(),
          ComfyuiGraph.toJson(argument(run.args(), binding.argument())));
    }
    for (ComfyuiWorkflow.MediaBinding binding : workflow.mediaBindings()) {
      CanvasFunctionFrozenReference reference =
          manifest.items().get(binding.referenceIndex()).reference();
      H3UploadedFile uploaded = state.uploads().get(reference.resourceId());
      if (uploaded == null) {
        throw new IllegalArgumentException("missing ComfyUI upload for a bound media reference");
      }
      ComfyuiGraph.write(
          graph, binding.nodeId(), binding.input(), ComfyuiGraph.textNode(uploaded.path()));
    }
    return graph;
  }

  /** 已注册 schema 的合法 args：显式值优先，缺失时回退到 argsSchema 声明的默认值。 */
  private CanvasJson argument(JsonObject args, String name) {
    CanvasJson value = args.values().get(name);
    if (value != null) {
      return value;
    }
    CanvasJson defaultValue = declaredDefault(name);
    if (defaultValue != null) {
      return defaultValue;
    }
    throw new IllegalArgumentException("workflow argument is missing: " + name);
  }

  private CanvasJson declaredDefault(String name) {
    if (!(workflow.argsSchema().values().get("properties") instanceof JsonObject properties)) {
      return null;
    }
    if (!(properties.values().get(name) instanceof JsonObject property)) {
      return null;
    }
    return property.values().get("default");
  }

  /** 按冻结 selector 顺序解释远端协议输出；未声明的节点与字段一律不取。 */
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

  private List<H3OutputDescriptor> awaitOutputs(
      CanvasFunctionExecutionContext context, String promptId) {
    long deadline = System.nanoTime() + settings.maxWaitMillis() * 1_000_000L;
    Duration pollInterval = Duration.ofMillis(settings.pollIntervalMillis());
    while (true) {
      ensureRunning(context);
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

  /** 按冻结顺序逐个下载并物化；每个流都有独立 deadline，且都由 try-with-resources 释放。 */
  private void materialize(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      List<H3OutputDescriptor> outputs) {
    for (int index = 0; index < outputs.size(); index++) {
      ensureRunning(context);
      try (H3ComfyDownload download = client.download(outputs.get(index))) {
        context.materializeOutput(run.output(index), download.content());
      } catch (IOException error) {
        throw new IllegalStateException("cannot close ComfyUI output stream", error);
      }
    }
  }

  /**
   * 引用媒体的准备与顺序：先完成可失败的纯文本准备，再逐个打开原始流、暂存并登记句柄，最后按「manifest 表格文本 -> 每个引用 label 文本 + 对应媒体片段」组织有序内容。
   *
   * <p>纯文本准备不产生宿主资源，因此先行完成；一旦开始暂存，每个句柄都在产生后立即登记进本方法持有的清理清单，任何后续失败（暂存失败、关闭原流失败或内容组装失败）都在同一所有权边界内
   * 清理全部已知句柄，且绝不调用 submit。
   */
  private List<PluginPromptContent> prepareContents(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest) {
    List<H3ReferenceManifest.Item> items = manifest.items();
    PluginPromptContent table = promptBuilder.manifestTable(run, manifest);
    List<PluginPromptContent> labels = new ArrayList<>(items.size());
    for (H3ReferenceManifest.Item item : items) {
      labels.add(promptBuilder.referenceLabel(item));
    }
    List<PluginPromptResources.StagedResource> staged = new ArrayList<>(items.size());
    try {
      List<PluginPromptContent> contents = new ArrayList<>(1 + items.size() * 2);
      contents.add(table);
      for (int i = 0; i < items.size(); i++) {
        contents.add(labels.get(i));
        contents.add(new PluginPromptContent.Media(stageMedia(context, items.get(i), staged)));
      }
      return contents;
    } catch (RuntimeException | Error failure) {
      discardAll(staged, failure);
      throw failure;
    }
  }

  /** 打开原始流并暂存句柄，句柄在关闭原始流之前就登记进调用方的清理清单：即使关闭原流失败，本次已产生的句柄也不会丢失清理。 */
  private PluginPromptResources.StagedResource stageMedia(
      CanvasFunctionExecutionContext context,
      H3ReferenceManifest.Item item,
      List<PluginPromptResources.StagedResource> staged) {
    try (CanvasFunctionResourceStream stream = context.openOriginal(item.reference())) {
      PluginPromptResources.StagedResource handle =
          promptResources.stage(
              item.reference().name(),
              item.reference().mediaType(),
              stream.content(),
              stream.size());
      staged.add(handle);
      return handle;
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot close H3 reference media " + item.reference().resourceId(), error);
    }
  }

  /**
   * 尽力清理全部已知句柄：每个句柄都尝试一次，主失败的身份与类型必须原样保留。
   *
   * <p>清理期的 {@link RuntimeException} 只按去敏类型日志（上传行的耐久过期仍是兜底），绝不进入主失败的传播链；清理期的 {@link Error} 按仓库
   * primary/suppressed 约定附加到主失败后继续清理剩余句柄，既不遮蔽也不吞掉。
   */
  private void discardAll(List<PluginPromptResources.StagedResource> staged, Throwable primary) {
    for (PluginPromptResources.StagedResource handle : staged) {
      try {
        promptResources.discard(handle);
      } catch (RuntimeException cleanup) {
        log.debug("H3 staged media cleanup failed type={}", cleanup.getClass().getSimpleName());
      } catch (Error cleanup) {
        if (cleanup != primary) {
          primary.addSuppressed(cleanup);
        }
      }
    }
  }

  private static void checkpoint(
      CanvasFunctionExecutionContext context, String stage, H3AdapterState state) {
    context.checkpoint(stage, state.encode());
  }

  private static void logCancelFailure(CanvasFunctionFrozenRun run, RuntimeException error) {
    log.debug(
        "ComfyUI best-effort cancel failed nodeId={} type={}",
        run.nodeId(),
        error.getClass().getSimpleName());
  }

  private static String clientId(CanvasFunctionFrozenRun run) {
    return "kk-studio-" + run.nodeId() + "-" + run.output(0).resourceId();
  }

  private static void ensureRunning(CanvasFunctionExecutionContext context) {
    if (!context.isRunning()) {
      throw new IllegalStateException("Canvas Function run is no longer RUNNING");
    }
  }

  private void requireFunction(CanvasFunctionFrozenRun run) {
    if (!workflow.id().equals(run.definition().name())) {
      throw new IllegalArgumentException(
          "ComfyUI adapter received another function: " + run.definition().name());
    }
  }

  private static String trustedExtension(CanvasFunctionFrozenReference reference) {
    return switch (reference.mediaType().toLowerCase()) {
      case "image/jpeg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      case "image/heic" -> ".heic";
      case "image/heif" -> ".heif";
      case "video/mp4" -> ".mp4";
      case "video/quicktime" -> ".mov";
      case "audio/wav", "audio/x-wav" -> ".wav";
      case "audio/mpeg" -> ".mp3";
      default -> throw new IllegalArgumentException("unsupported ComfyUI mediaType");
    };
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

  private static long requireNonnegative(Long value, String field) {
    if (value == null || value < 0L) {
      throw new IllegalArgumentException(field + " must be nonnegative");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ComfyUI polling was interrupted", interrupted);
    }
  }

  private static String extractText(JsonObject args, String field) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonText text) {
      return text.value();
    }
    throw new IllegalArgumentException(field + " must be a string");
  }

  private static String extractText(JsonObject args, String field, String defaultValue) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonText text) {
      return text.value();
    }
    if (json == null) {
      return defaultValue;
    }
    throw new IllegalArgumentException(field + " must be a string");
  }

  private static int extractInt(JsonObject args, String field, int defaultValue) {
    CanvasJson json = args.values().get(field);
    if (json instanceof JsonNumber number) {
      return number.value().intValueExact();
    }
    if (json == null) {
      return defaultValue;
    }
    throw new IllegalArgumentException(field + " must be an integer");
  }
}
