package fun.fengwk.kkstudio.plugin.comfyui;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * H3 提示词增强：seed 初始化、Prompt Agent 提交与等待。
 *
 * <p>提交边界之后的失败一律是 UNKNOWN，绝不自动重提；提交前的确定性输入校验与准备失败必须保持 FAILED。
 */
final class H3PromptEnhancer {

  private final ComfyuiSettings settings;
  private final H3PromptRequestBuilder promptBuilder;
  private final H3PromptStaging promptStaging;
  private final PluginPromptTasks promptTasks;

  H3PromptEnhancer(
      ComfyuiSettings settings,
      H3PromptRequestBuilder promptBuilder,
      H3PromptStaging promptStaging,
      PluginPromptTasks promptTasks) {
    this.settings = settings;
    this.promptBuilder = promptBuilder;
    this.promptStaging = promptStaging;
    this.promptTasks = promptTasks;
  }

  H3AdapterState enhance(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest,
      H3AdapterState state) {
    state = initializeSeed(context, state);
    if (state.enhancedPrompt() != null) {
      return state;
    }
    if (state.harnessThreadId() == null) {
      state = submit(context, run, manifest, state);
    }
    return await(context, state);
  }

  private H3AdapterState initializeSeed(
      CanvasFunctionExecutionContext context, H3AdapterState state) {
    if (state.seed() != null) {
      return state;
    }
    H3AdapterState seeded = state.withSeed(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
    checkpoint(context, ComfyuiCanvasFunctionAdapter.INITIALIZED, seeded);
    return seeded;
  }

  private H3AdapterState submit(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest,
      H3AdapterState state) {
    checkpoint(context, ComfyuiCanvasFunctionAdapter.PROMPT_SUBMITTING, state);
    // 提交前的确定性输入校验与准备：失败必须保持 FAILED，绝不进入 UNKNOWN 保守窗口。
    String promptAgentName =
        ComfyuiTexts.requireText(settings.promptAgentName(), "promptAgentName");
    String systemPrompt = promptBuilder.systemPrompt();
    List<PluginPromptContent> contents = promptStaging.prepare(context, run, manifest);
    UUID taskId;
    try {
      taskId = promptTasks.submit(promptAgentName, systemPrompt, contents);
    } catch (CanvasFunctionUnknownException unknown) {
      throw unknown;
    } catch (RuntimeException failure) {
      // 只声明结果未知，不回显可能的远端 URI/凭据/Cause。
      throw new CanvasFunctionUnknownException("H3 Prompt Agent submission outcome unknown");
    }
    H3AdapterState submitted = state.withHarnessThreadId(taskId);
    checkpoint(context, ComfyuiCanvasFunctionAdapter.PROMPT_WAITING, submitted);
    return submitted;
  }

  private H3AdapterState await(CanvasFunctionExecutionContext context, H3AdapterState state) {
    UUID taskId = Objects.requireNonNull(state.harnessThreadId(), "harnessThreadId");
    String enhancedPrompt =
        promptTasks.await(
            taskId, Duration.ofMillis(settings.promptMaxWaitMillis()), context::isRunning);
    H3AdapterState enhanced = state.withEnhancedPrompt(enhancedPrompt);
    checkpoint(context, ComfyuiCanvasFunctionAdapter.PROMPT_READY, enhanced);
    return enhanced;
  }

  private static void checkpoint(
      CanvasFunctionExecutionContext context, String stage, H3AdapterState state) {
    context.checkpoint(stage, state.encode());
  }
}
