package fun.fengwk.kkstudio.platform.canvas.function.h3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

  private final SystemSettings.MiniMaxH3 settings;
  private final H3MediaPreflight mediaPreflight;
  private final H3PromptRequestBuilder promptBuilder;
  private final HarnessOneShotService oneShotService;
  private final H3WorkflowBuilder workflowBuilder;
  private final ObjectProvider<StandardComfyuiClient> comfyClients;
  private final ObjectProvider<StorageBlobIngestService> ingestServices;
  private final ObjectMapper mapper;

  public MiniMaxH3CanvasFunctionAdapter(
      SystemSettingsSnapshot snapshot,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      HarnessOneShotService oneShotService,
      H3WorkflowBuilder workflowBuilder,
      ObjectProvider<StandardComfyuiClient> comfyClients,
      ObjectProvider<StorageBlobIngestService> ingestServices,
      ObjectMapper mapper) {
    this.settings = Objects.requireNonNull(snapshot, "snapshot").get().integrations().minimaxH3();
    this.mediaPreflight = Objects.requireNonNull(mediaPreflight, "mediaPreflight");
    this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
    this.oneShotService = Objects.requireNonNull(oneShotService, "oneShotService");
    this.workflowBuilder = Objects.requireNonNull(workflowBuilder, "workflowBuilder");
    this.comfyClients = Objects.requireNonNull(comfyClients, "comfyClients");
    this.ingestServices = Objects.requireNonNull(ingestServices, "ingestServices");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public List<CanvasFunctionModel> models() {
    return List.of(MODEL);
  }

  @Override
  public boolean enabled() {
    return settings.enabled();
  }

  @Override
  public String unavailableReason() {
    return settings.enabled()
        ? null
        : "MiniMax-H3 Ref2VA is disabled by kk-studio SystemSettings.integrations.minimaxH3";
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    requireModel(run);
    mediaPreflight.validate(run.manifest());
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
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
      AgentMessage promptRequest = promptBuilder.userMessage(run, manifest);
      UUID threadId =
          oneShotService.submit(
              requireText(settings.promptAgentName(), "promptAgentName"),
              promptBuilder.systemPrompt(),
              promptRequest,
              mediaPreflight(context, manifest));
      state = state.withHarnessThreadId(threadId);
      checkpoint(context, PROMPT_WAITING, state);
      stage = PROMPT_WAITING;
    } else if (PROMPT_SUBMITTING.equals(stage)) {
      throw unsafeRecovery(
          "H3 Prompt Agent submission may have happened without a durable threadId");
    }

    if (PROMPT_WAITING.equals(stage)) {
      UUID threadId = Objects.requireNonNull(state.harnessThreadId(), "harnessThreadId");
      String enhancedPrompt =
          oneShotService.await(
              threadId, Duration.ofMillis(settings.promptMaxWaitMillis()), context::isRunning);
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
        UUID resourceId = item.reference().resourceId();
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
              Duration.ofMillis(settings.comfyPollIntervalMillis()),
              Duration.ofMillis(settings.comfyMaxWaitMillis()));
      state = state.withOutput(output);
      checkpoint(context, COMFY_READY, state);
      stage = COMFY_READY;
    }

    if (COMFY_READY.equals(stage)) {
      ensureRunning(context);
      try (H3ComfyDownload download =
          comfy.download(Objects.requireNonNull(state.output(), "output"))) {
        context.materializeTarget(run.targetResourceId(), download.content());
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

  /**
   * 入队 preflight：把 USER 消息中按 manifest 顺序的 label 段落物化为全局存储 RESOURCE 内容（同一 store 事务内下载 canvas
   * original 字节并摄入，任何失败整体回滚）。消息结构：contents[0] 是 manifest 表格，contents[1+i] 是第 i 个引用的 label 段落；物化后在每个
   * label 段落之后追加对应 RESOURCE。
   */
  private AcceptancePreflight mediaPreflight(
      CanvasFunctionExecutionContext context, H3ReferenceManifest manifest) {
    StorageBlobIngestService ingestService = ingestServices.getIfAvailable();
    if (ingestService == null) {
      throw new IllegalStateException(
          "global storage is not available; H3 prompt media cannot be externalized");
    }
    List<H3ReferenceManifest.Item> items = manifest.items();
    return (tx, session, commands) -> {
      List<NewThreadCommand> prepared = new ArrayList<>(commands.size());
      for (NewThreadCommand command : commands) {
        if (!(command.payload() instanceof CustomMessageCommandPayload custom)) {
          prepared.add(command);
          continue;
        }
        AgentMessage message = custom.message();
        if (message.contents().size() != 1 + items.size()) {
          throw new IllegalStateException(
              "H3 prompt message must carry the manifest table plus one label per reference");
        }
        List<AgentMessageContent> contents = new ArrayList<>(1 + items.size() * 2);
        contents.add(message.contents().get(0));
        for (int i = 0; i < items.size(); i++) {
          AgentMessageContent label = message.contents().get(1 + i);
          if (!(label instanceof TextMessageContent)
              || !((TextMessageContent) label).text().startsWith("\nThe next attachment is ")) {
            throw new IllegalStateException("H3 prompt label mismatch at index " + i);
          }
          contents.add(label);
          contents.add(ingestMedia(session.id(), context, items.get(i), ingestService));
        }
        prepared.add(
            command.withPayload(
                new CustomMessageCommandPayload(new AgentMessage(message.role(), contents))));
      }
      return List.copyOf(prepared);
    };
  }

  private static ResourceMessageContent ingestMedia(
      UUID sessionId,
      CanvasFunctionExecutionContext context,
      H3ReferenceManifest.Item item,
      StorageBlobIngestService ingestService) {
    try (CanvasFunctionResourceStream stream = context.openOriginal(item.reference())) {
      byte[] bytes;
      try (InputStream content = stream.content()) {
        bytes = content.readAllBytes();
      } catch (IOException error) {
        throw new IllegalArgumentException(
            "cannot read H3 reference media " + item.reference().resourceId(), error);
      }
      UUID blobId = ingestService.ingest(sessionId, bytes, item.reference().mediaType());
      return ResourceMessageContent.media(blobId, item.reference().name());
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot close H3 reference media " + item.reference().resourceId(), error);
    }
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

  private static IllegalStateException unsafeRecovery(String message) {
    return new IllegalStateException(message + "; automatic resubmission is refused");
  }
}
