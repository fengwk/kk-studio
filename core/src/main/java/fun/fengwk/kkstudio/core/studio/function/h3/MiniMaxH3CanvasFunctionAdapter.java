package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** MiniMax-H3 Ref2VA Canvas Function adapter。 */
@Slf4j
public final class MiniMaxH3CanvasFunctionAdapter implements CanvasFunctionAdapter {

  public static final String MODEL_KEY = "minimax-h3-ref2va";

  static final String INITIALIZED = "H3_INITIALIZED";
  static final String PROMPT_SUBMITTING = "H3_PROMPT_SUBMITTING";
  static final String PROMPT_WAITING = "H3_PROMPT_WAITING";
  static final String PROMPT_READY = "H3_PROMPT_READY";
  static final String COMFY_UPLOADING = "H3_COMFY_UPLOADING";
  static final String COMFY_SUBMITTING = "H3_COMFY_SUBMITTING";
  static final String COMFY_WAITING = "H3_COMFY_WAITING";
  static final String COMFY_READY = "H3_COMFY_READY";
  static final String COMPLETE = "H3_COMPLETE";

  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          MODEL_KEY,
          "MiniMax-H3 Ref2VA",
          CanvasResourceKind.VIDEO,
          new CanvasFunctionReferencePolicy(
              Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO, CanvasResourceKind.AUDIO),
              12,
              Map.of(
                  CanvasResourceKind.IMAGE,
                  9,
                  CanvasResourceKind.VIDEO,
                  3,
                  CanvasResourceKind.AUDIO,
                  3)),
          List.of(
              CanvasFunctionParameterDefinition.enumParameter(
                  "ratio",
                  "Ratio",
                  true,
                  null,
                  List.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16")),
              CanvasFunctionParameterDefinition.integerParameter(
                  "duration", "Duration", false, 5, 4, 15)));

  private final MiniMaxH3Properties properties;
  private final H3MediaPreflight mediaPreflight;
  private final H3PromptRequestBuilder promptBuilder;
  private final HarnessOneShotService oneShotService;
  private final H3WorkflowBuilder workflowBuilder;
  private final ObjectProvider<StandardComfyuiClient> comfyClients;
  private final ObjectMapper mapper;

  public MiniMaxH3CanvasFunctionAdapter(
      MiniMaxH3Properties properties,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      HarnessOneShotService oneShotService,
      H3WorkflowBuilder workflowBuilder,
      ObjectProvider<StandardComfyuiClient> comfyClients,
      ObjectMapper mapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.mediaPreflight = Objects.requireNonNull(mediaPreflight, "mediaPreflight");
    this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
    this.oneShotService = Objects.requireNonNull(oneShotService, "oneShotService");
    this.workflowBuilder = Objects.requireNonNull(workflowBuilder, "workflowBuilder");
    this.comfyClients = Objects.requireNonNull(comfyClients, "comfyClients");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public List<CanvasFunctionModel> models() {
    return List.of(MODEL);
  }

  @Override
  public boolean enabled() {
    return properties.isEnabled();
  }

  @Override
  public String unavailableReason() {
    return properties.isEnabled()
        ? null
        : "MiniMax-H3 Ref2VA is disabled by kk-studio.canvas.function.minimax-h3.enabled";
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    requireModel(run);
    mediaPreflight.validate(run.manifest());
  }

  @Override
  public List<Long> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    requireModel(run);
    H3ReferenceManifest manifest = H3ReferenceManifest.from(run.manifest());
    H3AdapterState state = H3AdapterState.decode(run.adapterState(), mapper);
    String stage = run.stage();

    if ("QUEUED".equals(stage)) {
      state = state.withSeed(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
      checkpoint(context, INITIALIZED, state);
      stage = INITIALIZED;
    }

    if (INITIALIZED.equals(stage)) {
      checkpoint(context, PROMPT_SUBMITTING, state);
      AgentMessage promptRequest = promptRequest(context, run, manifest);
      long threadId =
          oneShotService.submit(
              "canvas-h3:" + run.nodeId(),
              requireText(properties.getPromptAgentName(), "promptAgentName"),
              new EnvironmentName(
                  requireText(properties.getPromptEnvironmentName(), "promptEnvironmentName")),
              promptBuilder.systemPrompt(),
              promptRequest);
      state = state.withHarnessThreadId(threadId);
      checkpoint(context, PROMPT_WAITING, state);
      stage = PROMPT_WAITING;
    } else if (PROMPT_SUBMITTING.equals(stage)) {
      throw unsafeRecovery(
          "H3 Prompt Agent submission may have happened without a durable threadId");
    }

    if (PROMPT_WAITING.equals(stage)) {
      long threadId = requirePositive(state.harnessThreadId(), "harnessThreadId");
      String enhancedPrompt =
          oneShotService.await(
              threadId,
              requirePositive(properties.getPromptMaxWait(), "promptMaxWait"),
              context::isRunning);
      state = state.withEnhancedPrompt(enhancedPrompt);
      checkpoint(context, PROMPT_READY, state);
      stage = PROMPT_READY;
    }

    if (PROMPT_READY.equals(stage)) {
      checkpoint(context, COMFY_UPLOADING, state);
      stage = COMFY_UPLOADING;
    }

    if (COMPLETE.equals(stage)) {
      return List.of(run.targetResourceId());
    }
    StandardComfyuiClient comfy = requireComfyClient();
    if (COMFY_UPLOADING.equals(stage)) {
      for (H3ReferenceManifest.Item item : manifest.items()) {
        long resourceId = item.reference().resourceId();
        if (state.uploads().containsKey(resourceId)) {
          continue;
        }
        ensureRunning(context);
        try (CanvasFunctionResourceStream original = context.openOriginal(item.reference())) {
          H3UploadedFile uploaded =
              comfy.upload(
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
      ObjectNode workflow =
          workflowBuilder.build(
              requireText(state.enhancedPrompt(), "enhancedPrompt"),
              (String) run.config().parameters().get("ratio"),
              (Integer) run.config().parameters().get("duration"),
              requireNonnegative(state.seed(), "seed"),
              run.targetResourceId(),
              manifest,
              state.uploads());
      checkpoint(context, COMFY_SUBMITTING, state);
      String promptId =
          comfy.submit(workflow, "kk-studio-" + run.nodeId() + "-" + run.targetResourceId());
      state = state.withPromptId(promptId);
      checkpoint(context, COMFY_WAITING, state);
      stage = COMFY_WAITING;
    } else if (COMFY_SUBMITTING.equals(stage)) {
      throw unsafeRecovery("H3 ComfyUI submission may have happened without a durable promptId");
    }

    if (COMFY_WAITING.equals(stage)) {
      H3OutputDescriptor output =
          awaitComfy(
              context,
              comfy,
              requireText(state.promptId(), "promptId"),
              requirePositive(properties.getComfyPollInterval(), "comfyPollInterval"),
              requirePositive(properties.getComfyMaxWait(), "comfyMaxWait"));
      state = state.withOutput(output);
      checkpoint(context, COMFY_READY, state);
      stage = COMFY_READY;
    }

    if (COMFY_READY.equals(stage)) {
      ensureRunning(context);
      try (H3ComfyDownload download =
          comfy.download(Objects.requireNonNull(state.output(), "output"))) {
        context.materializeTarget(
            run.targetResourceId(), download.mediaType(), download.length(), download.content());
      } catch (IOException error) {
        throw new IllegalStateException("cannot close ComfyUI output stream", error);
      }
      checkpoint(context, COMPLETE, state);
      stage = COMPLETE;
    }

    if (!COMPLETE.equals(stage)) {
      throw new IllegalArgumentException("unsupported H3 recovery stage: " + stage);
    }
    return List.of(run.targetResourceId());
  }

  @Override
  public void cancel(CanvasFunctionFrozenRun run) {
    H3AdapterState state;
    try {
      state = H3AdapterState.decode(run.adapterState(), mapper);
    } catch (RuntimeException error) {
      logCancelFailure(run, error);
      return;
    }
    if (state.harnessThreadId() != null) {
      try {
        oneShotService.stop(state.harnessThreadId());
      } catch (RuntimeException error) {
        logCancelFailure(run, error);
      }
    }
    if (state.promptId() != null) {
      try {
        StandardComfyuiClient comfy = comfyClients.getIfAvailable();
        if (comfy != null) {
          comfy.cancelPending(state.promptId());
        }
      } catch (RuntimeException error) {
        logCancelFailure(run, error);
      }
    }
  }

  private static void logCancelFailure(CanvasFunctionFrozenRun run, RuntimeException error) {
    log.debug(
        "MiniMax-H3 best-effort cancel failed nodeId={} type={}",
        run.nodeId(),
        error.getClass().getSimpleName());
  }

  private AgentMessage promptRequest(
      CanvasFunctionExecutionContext context,
      CanvasFunctionFrozenRun run,
      H3ReferenceManifest manifest) {
    long expires = properties.getPresignExpirySeconds();
    if (expires <= 0L) {
      throw new IllegalArgumentException("presignExpirySeconds must be positive");
    }
    return promptBuilder.userMessage(
        run, manifest, item -> context.presignOriginal(item.reference(), expires));
  }

  private static H3OutputDescriptor awaitComfy(
      CanvasFunctionExecutionContext context,
      StandardComfyuiClient comfy,
      String promptId,
      Duration pollInterval,
      Duration maxWait) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    while (true) {
      ensureRunning(context);
      H3ComfyHistory history = comfy.history(promptId);
      switch (history.status()) {
        case SUCCESS -> {
          return Objects.requireNonNull(history.output(), "history.output");
        }
        case ERROR -> throw new IllegalStateException(
            "ComfyUI H3 execution failed: " + history.error());
        case PENDING -> {
          if (System.nanoTime() - deadline >= 0L) {
            throw new IllegalStateException("ComfyUI H3 execution timed out after " + maxWait);
          }
          sleep(pollInterval);
        }
      }
    }
  }

  private static void checkpoint(
      CanvasFunctionExecutionContext context, String stage, H3AdapterState state) {
    context.checkpoint(stage, state.encode());
  }

  private static void ensureRunning(CanvasFunctionExecutionContext context) {
    if (!context.isRunning()) {
      throw new IllegalStateException("Canvas Function run is no longer RUNNING");
    }
  }

  private StandardComfyuiClient requireComfyClient() {
    return Objects.requireNonNull(
        comfyClients.getIfAvailable(), "Standard ComfyUI client is required for MiniMax-H3");
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
      default -> throw new IllegalArgumentException("unsupported H3 mediaType");
    };
  }

  private static void requireModel(CanvasFunctionFrozenRun run) {
    if (!MODEL_KEY.equals(run.model().key())) {
      throw new IllegalArgumentException("MiniMax-H3 adapter received another model");
    }
  }

  private static long requirePositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
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

  private static Duration requirePositive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
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

  private static IllegalStateException unsafeRecovery(String message) {
    return new IllegalStateException(message + "; automatic resubmission is refused");
  }
}
