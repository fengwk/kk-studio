package fun.fengwk.kkstudio.platform.canvas.function.opencli;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

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
public class GptImage2CanvasFunctionAdapter implements CanvasFunctionAdapter {

  static final long MAX_REFERENCE_SIZE = 20L * 1024 * 1024;
  static final String MODEL_KEY = "gpt-image-2";
  private static final String UNKNOWN_SUBMISSION =
      "GPT Image submission outcome unknown; automatic resubmission is forbidden";
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          MODEL_KEY,
          "GPT Image 2",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 20, Map.of()),
          List.of(
              CanvasFunctionParameterDefinition.enumParameter(
                  "ratio",
                  "Ratio",
                  false,
                  "auto",
                  List.of("auto", "1:1", "3:4", "9:16", "4:3", "16:9"))));

  private final SystemSettings.Integrations integrations;
  private final OpenCliHubClient client;
  private final OpenCliReferenceUploader uploader;

  public GptImage2CanvasFunctionAdapter(SystemSettingsSnapshot snapshot, OpenCliHubClient client) {
    this.integrations = Objects.requireNonNull(snapshot, "snapshot").get().integrations();
    this.client = Objects.requireNonNull(client, "client");
    uploader = new OpenCliReferenceUploader(client);
  }

  @Override
  public List<CanvasFunctionModel> models() {
    return List.of(MODEL);
  }

  @Override
  public boolean enabled() {
    return integrations.openCliHub().enabled() && integrations.gptImage2().paidEnabled();
  }

  @Override
  public String unavailableReason() {
    return enabled() ? null : "GPT Image 2 generation is disabled";
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    if (!MODEL_KEY.equals(run.model().key())) {
      throw new IllegalArgumentException("unsupported GPT Image model");
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
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    preflight(run);
    Map<String, Object> state = OpenCliAdapterState.copy(run.adapterState());
    String executionId =
        OpenCliAdapterState.optionalString(state, OpenCliAdapterState.EXECUTION_ID);
    if ("GPT_IMAGE_SUBMITTING".equals(run.stage()) && executionId == null) {
      throw new IllegalStateException(UNKNOWN_SUBMISSION);
    }

    List<OpenCliAdapterState.UploadedInput> uploads;
    if (executionId == null) {
      OpenCliReferenceUploader.UploadResult uploadResult = uploader.uploadAll(context, run, state);
      uploads = uploadResult.uploads();
      state = uploadResult.state();
      context.checkpoint("GPT_IMAGE_SUBMITTING", state);
      Execution submitted =
          client.execute(
              buildArgv(run, uploads), integrations.gptImage2().hubExecutionTimeoutMillis());
      executionId = submitted.id();
      state.put(OpenCliAdapterState.EXECUTION_ID, executionId);
      context.checkpoint("GPT_IMAGE_POLLING", state);
    } else {
      uploads = OpenCliAdapterState.uploads(state);
      requireCompleteUploads(run, uploads);
    }

    Execution execution =
        awaitTerminal(
            context, executionId, Duration.ofMillis(integrations.gptImage2().maxWaitMillis()));
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
      UUID resourceId = context.materializeTarget(run.targetResourceId(), stream.content());
      return List.of(resourceId);
    } catch (IOException exception) {
      throw new UncheckedIOException("failed to close GPT Image Hub resource", exception);
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
    String ratio = (String) run.config().parameters().get("ratio");
    String rendered = CanvasPromptRenderer.gptImage(run);
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
            .formatted(ratio, rendered)
            .strip();
    List<String> argv = new ArrayList<>();
    argv.addAll(List.of("chatgpt-agent", "ask", instruction));
    for (OpenCliAdapterState.UploadedInput upload : uploads) {
      argv.add("--file");
      argv.add(upload.resourcePath());
    }
    argv.add("--timeout");
    argv.add(Integer.toString(integrations.gptImage2().askTimeoutSeconds()));
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
}
