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
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.oneshot.HarnessOneShotService;
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
      PreparedMedia preparedMedia = mediaPreflight(context, manifest);
      UUID threadId;
      try {
        threadId =
            oneShotService.submit(
                requireText(settings.promptAgentName(), "promptAgentName"),
                promptBuilder.systemPrompt(),
                promptRequest,
                preparedMedia.preflight());
      } catch (RuntimeException failure) {
        preparedMedia.staged().forEach(this::discardBestEffort);
        throw failure;
      }
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
   * 入队前先在 Harness 事务外把 manifest 资源准备为 READY upload；preflight 事务内只转移引用并改写 USER 消息。消息结构：可选的 leading
   * {@code <system-reminder>} 段落，随后是 manifest 表格，再往后是第 i 个引用的 label 段落；物化后在每个 label 段落之后追加对应
   * RESOURCE。
   */
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

  /** 合成 USER 消息允许携带 trusted system 文本的前导提醒段，它不参与 manifest/label 结构校验。 */
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
