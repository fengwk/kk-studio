package fun.fengwk.kkstudio.plugin.comfyui;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个冻结工作流的 Canvas Function adapter：{@code DIRECT} 直接应用已声明绑定，{@code H3} 保留 MiniMax-H3 Ref2VA
 * 的增强/帧长语义。
 *
 * <p>adapter 在装配时冻结配置修订产出的不可变 {@link ComfyuiWorkflow}，因此热更新不会改变已冻结 Run 的解释：图、参数/媒体绑定与输出 selector
 * 都取自 同一份快照。引用媒体通过 Prompt 媒体端口在接受事务外统一暂存，由宿主在接受事务内物化为 durable RESOURCE；因停止、失败或回滚未消费的暂存交由 Storage
 * maintenance 回收。
 *
 * <p>这里只保留编排：参数解析与构图在 {@link ComfyuiWorkflowBinder}，manifest 上传在 {@link ComfyuiManifestUploader}，H3
 * 提示词在 {@link H3PromptEnhancer}，远端结果解释与物化在 {@link ComfyuiResultMaterializer}。
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
  private final PluginPromptTasks promptTasks;
  private final ComfyuiWorkflowBinder binder;
  private final ComfyuiManifestUploader uploader;
  private final ComfyuiResultMaterializer materializer;
  private final H3PromptEnhancer promptEnhancer;
  private final String unknownSubmission;

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
    this.promptTasks = promptTasks;
    // 未知结果必须能被定位到具体工作流：同一客户端并发承载多个冻结工作流。
    this.unknownSubmission =
        "workflow "
            + workflow.id()
            + " ComfyUI submission outcome unknown; automatic resubmission is forbidden";
    // H3 策略只能在绑定 Prompt 任务与媒体端口的部署里装配；未绑定即确定性失败，不做静默降级。
    if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3
        && (mediaPreflight == null
            || promptBuilder == null
            || promptTasks == null
            || promptResources == null)) {
      throw new IllegalStateException("H3 workflows require the Prompt task and media ports");
    }
    this.binder = new ComfyuiWorkflowBinder(workflow, mediaPreflight);
    this.uploader = new ComfyuiManifestUploader(client);
    this.materializer = new ComfyuiResultMaterializer(workflow, settings, client);
    this.promptEnhancer =
        workflow.strategy() == ComfyuiWorkflow.Strategy.H3
            ? new H3PromptEnhancer(
                settings,
                promptBuilder,
                new H3PromptStaging(promptBuilder, promptResources),
                promptTasks)
            : null;
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
    binder.preflight(run);
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
      state = promptEnhancer.enhance(context, run, manifest, state);
    }
    checkpoint(context, COMFY_UPLOADING, state);
    state = uploader.upload(context, run, manifest, state);

    ObjectNode workflowJson = binder.bind(run, manifest, state);
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
      List<H3OutputDescriptor> outputs = materializer.awaitOutputs(context, state.promptId());
      state = state.withOutputs(outputs);
      checkpoint(context, COMFY_READY, state);
      stage = COMFY_READY;
    }
    if (state.outputs() != null && !COMPLETE.equals(stage)) {
      materializer.materialize(context, run, state.outputs());
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

  private void requireFunction(CanvasFunctionFrozenRun run) {
    if (!workflow.id().equals(run.definition().name())) {
      throw new IllegalArgumentException(
          "ComfyUI adapter received another function: " + run.definition().name());
    }
  }
}
