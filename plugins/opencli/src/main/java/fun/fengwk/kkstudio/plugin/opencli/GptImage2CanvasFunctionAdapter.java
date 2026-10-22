package fun.fengwk.kkstudio.plugin.opencli;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.HubResourceStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 通过 OpenCLI Hub + chatgpt-agent 执行 GPT Image 2。 */
@Slf4j
public class GptImage2CanvasFunctionAdapter implements CanvasFunctionAdapter {

  static final long MAX_REFERENCE_SIZE = 20L * 1024 * 1024;
  public static final String FUNCTION_NAME = "gpt-image-2";
  private static final String UNKNOWN_SUBMISSION =
      "GPT Image submission outcome unknown; automatic resubmission is forbidden";

  private static final JsonObject ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "description": "GPT Image 2 parameters",
            "additionalProperties": false,
            "properties": {
              "prompt": {
                "type": "string",
                "description": "Prompt text"
              },
              "ratio": {
                "type": "string",
                "description": "Aspect ratio",
                "enum": ["auto", "1:1", "3:4", "9:16", "4:3", "16:9"],
                "default": "auto"
              },
              "references": {
                "type": "array",
                "description": "Reference images",
                "items": {
                  "type": "resourceReference",
                  "description": "Reference resource"
                },
                "minItems": 0,
                "maxItems": 20
              }
            },
            "required": ["prompt"]
          }
          """);

  private static final CanvasFunctionDefinition DEFINITION =
      CanvasFunctionDefinition.of(
          FUNCTION_NAME,
          "GPT Image 2",
          ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 20, Map.of()));

  private final OpenCliSettings settings;
  private final OpenCliHubClient client;
  private final OpenCliReferenceUploader uploader;

  public GptImage2CanvasFunctionAdapter(OpenCliSettings settings, OpenCliHubClient client) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.client = Objects.requireNonNull(client, "client");
    uploader = new OpenCliReferenceUploader(client);
  }

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return List.of(DEFINITION);
  }

  @Override
  public boolean enabled() {
    return settings.enabled() && settings.gptImage2().paidEnabled();
  }

  @Override
  public String unavailableReason() {
    return enabled() ? null : "GPT Image 2 generation is disabled";
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    if (!FUNCTION_NAME.equals(run.definition().name())) {
      throw new IllegalArgumentException(
          "unsupported GPT Image function: " + run.definition().name());
    }
    String prompt = extractText(run.args(), "prompt");
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("GPT Image prompt must not be blank");
    }
    if (run.manifest().size() > 20) {
      throw new IllegalArgumentException("GPT Image accepts at most 20 references");
    }
    for (CanvasFunctionFrozenReference reference : run.manifest()) {
      if (reference.kind() != CanvasResourceKind.IMAGE
          || !reference.mediaType().toLowerCase().startsWith("image/")
          || reference.sizeBytes() <= 0L
          || reference.sizeBytes() > MAX_REFERENCE_SIZE) {
        throw new IllegalArgumentException(
            "GPT Image references must be image/* and at most 20 MiB each");
      }
    }
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    preflight(run);
    Map<String, Object> state = OpenCliAdapterState.copy(run.adapterState());
    String executionId =
        OpenCliAdapterState.optionalString(state, OpenCliAdapterState.EXECUTION_ID);
    if (executionId != null) {
      return;
    }
    OpenCliReferenceUploader.UploadResult uploadResult = uploader.uploadAll(context, run, state);
    List<OpenCliAdapterState.UploadedInput> uploads = uploadResult.uploads();
    state = uploadResult.state();
    context.checkpoint("GPT_IMAGE_SUBMITTING", state);
    Execution submitted;
    try {
      submitted =
          client.execute(buildArgv(run, uploads), settings.gptImage2().hubExecutionTimeoutMillis());
    } catch (RuntimeException failure) {
      // 提交失败意味着外部任务可能已创建；只按 type 记录，不把原始 message/cause 交给人工决议。
      log.warn("GPT Image submission outcome unknown type={}", failure.getClass().getSimpleName());
      throw new CanvasFunctionUnknownException(UNKNOWN_SUBMISSION);
    }
    executionId = submitted.id();
    state.put(OpenCliAdapterState.EXECUTION_ID, executionId);
    context.checkpoint("GPT_IMAGE_POLLING", state);
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    preflight(run);
    Map<String, Object> state = OpenCliAdapterState.copy(run.adapterState());
    String executionId =
        OpenCliAdapterState.optionalString(state, OpenCliAdapterState.EXECUTION_ID);
    if (executionId == null) {
      throw new CanvasFunctionUnknownException(UNKNOWN_SUBMISSION);
    }

    List<OpenCliAdapterState.UploadedInput> uploads = OpenCliAdapterState.uploads(state);
    requireCompleteUploads(run, uploads);

    Execution execution =
        awaitTerminal(
            context, executionId, Duration.ofMillis(settings.gptImage2().maxWaitMillis()));
    if (execution.status() != ExecutionStatus.SUCCEEDED) {
      throw new OpenCliHubException("GPT Image Hub execution ended as " + execution.status());
    }
    if (execution.resources().size() != 1
        || !execution.resources().get(0).mimeType().toLowerCase().startsWith("image/")) {
      throw new OpenCliHubException(
          "GPT Image requires exactly one image/* Hub execution resource");
    }
    context.checkpoint("GPT_IMAGE_MATERIALIZING", state);
    ExecutionResource output = execution.resources().get(0);
    try (HubResourceStream stream = client.openResource(output)) {
      UUID resourceId = context.materializeOutput(run.output(0), stream.content());
      return List.of(resourceId);
    } catch (IOException exception) {
      // 次要关闭失败的原始 IOException 可能含 URI/path；只按 type 记录并抛出固定文案。
      log.warn(
          "failed to close GPT Image Hub resource type={}", exception.getClass().getSimpleName());
      throw new UncheckedIOException(new IOException("failed to close GPT Image Hub resource"));
    }
  }

  @Override
  public void cancel(CanvasFunctionFrozenRun run) {
    String executionId =
        OpenCliAdapterState.optionalString(run.adapterState(), OpenCliAdapterState.EXECUTION_ID);
    if (executionId != null) {
      client.cancelPendingBestEffort(executionId);
    }
  }

  private List<String> buildArgv(
      CanvasFunctionFrozenRun run, List<OpenCliAdapterState.UploadedInput> uploads) {
    requireCompleteUploads(run, uploads);
    String ratio = extractText(run.args(), "ratio", "auto");
    String prompt = extractText(run.args(), "prompt");
    String instruction =
        """
        Use GPT Image 2 to generate exactly one image with aspect ratio %s.
        This is a zero-context request: do not rely on any earlier conversation.
        The attached files are reference images in the exact order supplied. Each inline marker \
        [Reference image N] in the user request refers to attachment N.
        Preserve the user's original wording and the reference relationships.
        You must produce exactly one downloadable image artifact and no additional file artifacts.

        User request:
        ---
        %s
        ---
        """
            .formatted(ratio, prompt)
            .strip();
    List<String> argv = new ArrayList<>();
    argv.addAll(List.of("chatgpt-agent", "ask", instruction));
    for (OpenCliAdapterState.UploadedInput upload : uploads) {
      argv.add("--file");
      argv.add(upload.resourcePath());
    }
    argv.add("--timeout");
    argv.add(Integer.toString(settings.gptImage2().askTimeoutSeconds()));
    return List.copyOf(argv);
  }

  private Execution awaitTerminal(
      CanvasFunctionExecutionContext context, String executionId, Duration maxWait) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    Execution execution = client.getExecution(executionId, 0);
    while (!execution.status().terminal()) {
      if (!context.isRunning()) {
        throw new IllegalStateException("Canvas Function run is no longer running");
      }
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0L) {
        throw new OpenCliHubException("GPT Image Hub execution exceeded maximum wait");
      }
      int waitSeconds =
          (int) Math.max(1L, Math.min(120L, Duration.ofNanos(remainingNanos).toSeconds()));
      execution = client.getExecution(executionId, waitSeconds);
    }
    return execution;
  }

  private static void requireCompleteUploads(
      CanvasFunctionFrozenRun run, List<OpenCliAdapterState.UploadedInput> uploads) {
    if (uploads.size() != run.manifest().size()) {
      throw new IllegalArgumentException("checkpoint uploads do not cover frozen manifest");
    }
    for (int index = 0; index < uploads.size(); index++) {
      if (!uploads.get(index).resourceId().equals(run.manifest().get(index).resourceId())) {
        throw new IllegalArgumentException("checkpoint uploads do not match frozen manifest");
      }
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
}
