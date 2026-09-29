package fun.fengwk.kkstudio.plugin.canvascomfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.platform.harness.oneshot.OneShotTicket;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MiniMax-H3 Ref2VA Canvas Function adapter。
 *
 * <p>引用媒体在 Harness acceptance 事务外统一 stage；acceptance 短事务只校验 READY blob 事实并原子转移 Session
 * 引用，因停止、失败或回滚未消费的 upload 交由 Storage maintenance 回收。
 */
@Slf4j
public final class MiniMaxH3CanvasFunctionAdapter implements CanvasFunctionAdapter {

  public static final String FUNCTION_NAME = "minimax-h3-ref2va";

  static final String INITIALIZED = "H3_INITIALIZED";
  static final String PROMPT_SUBMITTING = "H3_PROMPT_SUBMITTING";
  static final String PROMPT_WAITING = "H3_PROMPT_WAITING";
  static final String PROMPT_READY = "H3_PROMPT_READY";
  static final String COMFY_UPLOADING = "H3_COMFY_UPLOADING";
  static final String COMFY_SUBMITTING = "H3_COMFY_SUBMITTING";
  static final String COMFY_WAITING = "H3_COMFY_WAITING";
  static final String COMFY_READY = "H3_COMFY_READY";
  static final String COMPLETE = "H3_COMPLETE";

  private static final JsonObject ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "description": "MiniMax-H3 Ref2VA parameters",
            "additionalProperties": false,
            "properties": {
              "prompt": {
                "type": "string",
                "description": "Prompt text"
              },
              "ratio": {
                "type": "string",
                "description": "Aspect ratio",
                "enum": ["21:9", "16:9", "4:3", "1:1", "3:4", "9:16"],
                "default": "16:9"
              },
              "duration": {
                "type": "integer",
                "description": "Duration in seconds",
                "minimum": 4,
                "maximum": 15,
                "default": 5
              },
              "references": {
                "type": "array",
                "description": "Reference resources",
                "items": {
                  "type": "resourceReference",
                  "description": "Reference resource"
                },
                "minItems": 0,
                "maxItems": 12
              }
            },
            "required": ["prompt"]
          }
          """);

  private static final CanvasFunctionReferencePolicy REFERENCE_POLICY =
      new CanvasFunctionReferencePolicy(
          Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO, CanvasResourceKind.AUDIO),
          12,
          Map.of(
              CanvasResourceKind.IMAGE,
              9,
              CanvasResourceKind.VIDEO,
              3,
              CanvasResourceKind.AUDIO,
              3));

  private static final CanvasFunctionDefinition DEFINITION =
      CanvasFunctionDefinition.of(
          FUNCTION_NAME,
          "MiniMax-H3 Ref2VA",
          ARGS_SCHEMA,
          CanvasResourceKind.VIDEO,
          REFERENCE_POLICY);

  private final SystemSettings.MiniMaxH3 settings;
  private final H3MediaPreflight mediaPreflight;
  private final H3PromptRequestBuilder promptBuilder;
  private final HarnessOneShotService oneShotService;
  private final H3WorkflowBuilder workflowBuilder;
  private final ObjectProvider<StandardComfyuiClient> comfyClients;
  private final StorageUploadService uploadService;
  private final SessionBlobRefManager refManager;
  private final ObjectMapper mapper;

  public MiniMaxH3CanvasFunctionAdapter(
      SystemSettingsSnapshot snapshot,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      HarnessOneShotService oneShotService,
      H3WorkflowBuilder workflowBuilder,
      ObjectProvider<StandardComfyuiClient> comfyClients,
      StorageUploadService uploadService,
      SessionBlobRefManager refManager,
      ObjectMapper mapper) {
    this.settings = Objects.requireNonNull(snapshot, "snapshot").get().integrations().minimaxH3();
    this.mediaPreflight = Objects.requireNonNull(mediaPreflight, "mediaPreflight");
    this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
    this.oneShotService = Objects.requireNonNull(oneShotService, "oneShotService");
    this.workflowBuilder = Objects.requireNonNull(workflowBuilder, "workflowBuilder");
    this.comfyClients = Objects.requireNonNull(comfyClients, "comfyClients");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return List.of(DEFINITION);
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
    requireFunction(run);
    String prompt = extractText(run.args(), "prompt");
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("H3 prompt must not be blank");
    }
    mediaPreflight.validate(run.manifest());
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    requireFunction(run);
    preflight(run);
    H3ReferenceManifest manifest = H3ReferenceManifest.from(run.manifest());
    H3AdapterState state = H3AdapterState.decode(run.adapterState(), mapper);
    String stage = run.stage();

    if (PROMPT_SUBMITTING.equals(stage) && state.harnessThreadId() == null) {
      throw new CanvasFunctionUnknownException(
          "H3 Prompt Agent submission outcome unknown; automatic resubmission is forbidden");
    }
    if (COMFY_SUBMITTING.equals(stage) && state.promptId() == null) {
      throw new CanvasFunctionUnknownException(
          "H3 ComfyUI submission outcome unknown; automatic resubmission is forbidden");
    }

    if (state.promptId() != null) {
      return;
    }

    if (state.seed() == null) {
      state = state.withSeed(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
      checkpoint(context, INITIALIZED, state);
      stage = INITIALIZED;
    }

    if (state.enhancedPrompt() == null) {
      if (state.harnessThreadId() == null) {
        checkpoint(context, PROMPT_SUBMITTING, state);
        // 提交前的确定性输入校验与准备：失败必须保持 FAILED，绝不进入 UNKNOWN 保守窗口。
        String promptAgentName = requireText(settings.promptAgentName(), "promptAgentName");
        String systemPrompt = promptBuilder.systemPrompt();
        AgentMessage promptRequest = promptBuilder.userMessage(run, manifest);
        PreparedMedia preparedMedia = mediaPreflight(context, manifest);
        UUID threadId;
        try {
          threadId =
              oneShotService
                  .submit(promptAgentName, systemPrompt, promptRequest, preparedMedia.preflight())
                  .threadId();
        } catch (RuntimeException failure) {
          // submit 已越过输入校验：异常不能证明接受事实未成立，禁止删除可能已被消费的 staged media。
          if (failure instanceof CanvasFunctionUnknownException unknown) {
            throw unknown;
          }
          throw new CanvasFunctionUnknownException(
              "H3 Prompt Agent submission outcome unknown: " + failure.getMessage());
        }
        state = state.withHarnessThreadId(threadId);
        checkpoint(context, PROMPT_WAITING, state);
      }

      UUID threadId = Objects.requireNonNull(state.harnessThreadId(), "harnessThreadId");
      String enhancedPrompt =
          oneShotService.await(
              OneShotTicket.forThread(threadId),
              Duration.ofMillis(settings.promptMaxWaitMillis()),
              context::isRunning);
      state = state.withEnhancedPrompt(enhancedPrompt);
      checkpoint(context, PROMPT_READY, state);
    }

    checkpoint(context, COMFY_UPLOADING, state);
    StandardComfyuiClient comfy = requireComfyClient();
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

    String ratio = extractText(run.args(), "ratio", "16:9");
    int duration = extractInt(run.args(), "duration", 5);
    ObjectNode workflow =
        workflowBuilder.build(
            requireText(state.enhancedPrompt(), "enhancedPrompt"),
            ratio,
            duration,
            requireNonnegative(state.seed(), "seed"),
            run.output(0).resourceId(),
            manifest,
            state.uploads());
    checkpoint(context, COMFY_SUBMITTING, state);
    String promptId;
    try {
      promptId =
          comfy.submit(workflow, "kk-studio-" + run.nodeId() + "-" + run.output(0).resourceId());
    } catch (RuntimeException failure) {
      throw new CanvasFunctionUnknownException(
          "H3 ComfyUI submission outcome unknown: " + failure.getMessage());
    }
    state = state.withPromptId(promptId);
    checkpoint(context, COMFY_WAITING, state);
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    requireFunction(run);
    preflight(run);
    H3AdapterState state = H3AdapterState.decode(run.adapterState(), mapper);
    String stage = run.stage();

    if (COMPLETE.equals(stage)) {
      return run.outputResourceIds();
    }

    if (state.promptId() == null) {
      throw new CanvasFunctionUnknownException(
          "H3 ComfyUI promptId is missing; automatic resubmission is forbidden");
    }

    StandardComfyuiClient comfy = requireComfyClient();
    if (state.output() == null) {
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

    if (state.output() != null && !COMPLETE.equals(stage)) {
      ensureRunning(context);
      try (H3ComfyDownload download =
          comfy.download(Objects.requireNonNull(state.output(), "output"))) {
        context.materializeOutput(run.output(0), download.content());
      } catch (IOException error) {
        throw new IllegalStateException("cannot close ComfyUI output stream", error);
      }
      checkpoint(context, COMPLETE, state);
    }

    return run.outputResourceIds();
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

  private PreparedMedia mediaPreflight(
      CanvasFunctionExecutionContext context, H3ReferenceManifest manifest) {
    List<H3ReferenceManifest.Item> items = manifest.items();
    List<StagedMedia> staged = new ArrayList<>(items.size());
    try {
      for (H3ReferenceManifest.Item item : items) {
        staged.add(stageMedia(context, item));
      }
    } catch (RuntimeException failure) {
      staged.forEach(this::discardBestEffort);
      throw failure;
    }
    AcceptancePreflight preflight =
        (tx, session, commands) -> {
          List<NewThreadCommand> prepared = new ArrayList<>(commands.size());
          for (NewThreadCommand command : commands) {
            if (!(command.payload() instanceof CustomMessageCommandPayload custom)) {
              prepared.add(command);
              continue;
            }
            AgentMessage message = custom.message();
            int offset = hasLeadingReminder(message) ? 1 : 0;
            if (message.contents().size() != offset + 1 + items.size()) {
              throw new IllegalStateException(
                  "H3 prompt message must carry the manifest table plus one label per reference");
            }
            List<AgentMessageContent> contents = new ArrayList<>(offset + 1 + items.size() * 2);
            contents.addAll(message.contents().subList(0, offset + 1));
            for (int i = 0; i < items.size(); i++) {
              AgentMessageContent label = message.contents().get(offset + 1 + i);
              if (!(label instanceof TextMessageContent)
                  || !((TextMessageContent) label).text().startsWith("\nThe next attachment is ")) {
                throw new IllegalStateException("H3 prompt label mismatch at index " + i);
              }
              contents.add(label);
              contents.add(consumeMedia(session.id(), items.get(i), staged.get(i)));
            }
            prepared.add(
                command.withPayload(
                    new CustomMessageCommandPayload(new AgentMessage(message.role(), contents))));
          }
          return List.copyOf(prepared);
        };
    return new PreparedMedia(preflight, staged);
  }

  private static boolean hasLeadingReminder(AgentMessage message) {
    AgentMessageContent first = message.contents().get(0);
    return first instanceof TextMessageContent text && SystemReminder.isReminderText(text.text());
  }

  private StagedMedia stageMedia(
      CanvasFunctionExecutionContext context, H3ReferenceManifest.Item item) {
    try (CanvasFunctionResourceStream stream = context.openOriginal(item.reference())) {
      StorageUploadService.StagedUpload upload =
          uploadService.stage(
              item.reference().name(),
              item.reference().mediaType(),
              stream.content(),
              stream.size());
      if (!item.reference().mediaType().equals(upload.mediaType())
          || stream.size() != upload.sizeBytes()) {
        discardBestEffort(new StagedMedia(upload.uploadId(), upload.blobId()));
        throw new IllegalArgumentException(
            "H3 reference media failed integrity validation: " + item.reference().resourceId());
      }
      return new StagedMedia(upload.uploadId(), upload.blobId());
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot close H3 reference media " + item.reference().resourceId(), error);
    }
  }

  private ResourceMessageContent consumeMedia(
      UUID sessionId, H3ReferenceManifest.Item item, StagedMedia staged) {
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(staged.uploadId());
    if (!staged.blobId().equals(ready.blobId())) {
      throw new IllegalArgumentException(
          "H3 reference media failed integrity validation: " + item.reference().resourceId());
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(staged.uploadId());
    return ResourceMessageContent.media(ready.blobId(), ready.filename());
  }

  private void discardBestEffort(StagedMedia staged) {
    try {
      uploadService.delete(staged.uploadId());
    } catch (RuntimeException ignored) {
      // Durable upload expiry remains the fallback.
    }
  }

  private record PreparedMedia(AcceptancePreflight preflight, List<StagedMedia> staged) {

    private PreparedMedia {
      staged = List.copyOf(staged);
    }
  }

  private record StagedMedia(UUID uploadId, UUID blobId) {}

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

  private static void requireFunction(CanvasFunctionFrozenRun run) {
    if (!FUNCTION_NAME.equals(run.definition().name())) {
      throw new IllegalArgumentException(
          "MiniMax-H3 adapter received another function: " + run.definition().name());
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
