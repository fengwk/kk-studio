package fun.fengwk.kkstudio.core.studio.function.opencli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 通过 OpenCLI Hub + jimeng-agent 执行 Seedance 2.0 系列。 */
public class SeedanceCanvasFunctionAdapter implements CanvasFunctionAdapter {

  private static final Pattern ASSET_ID = Pattern.compile("[0-9a-f]{16}");
  private static final Set<String> MODEL_KEYS =
      Set.of("seedance2.0", "seedance2.0fast", "seedance2.0_vip", "seedance2.0fast_vip");
  private static final List<String> RATIOS = List.of("1:1", "3:4", "16:9", "4:3", "9:16", "21:9");
  private static final String UNKNOWN_SUBMISSION =
      "Seedance submission outcome unknown; automatic resubmission is forbidden";
  private static final List<CanvasFunctionModel> MODELS =
      List.of(
          model("seedance2.0", "Seedance 2.0"),
          model("seedance2.0fast", "Seedance 2.0 Fast"),
          model("seedance2.0_vip", "Seedance 2.0 VIP"),
          model("seedance2.0fast_vip", "Seedance 2.0 Fast VIP"));

  private final OpenCliHubProperties hubProperties;
  private final SeedanceCanvasProperties properties;
  private final OpenCliHubClient client;
  private final OpenCliReferenceUploader uploader;
  private final ObjectMapper mapper;
  private final Sleeper sleeper;

  public SeedanceCanvasFunctionAdapter(
      OpenCliHubProperties hubProperties,
      SeedanceCanvasProperties properties,
      OpenCliHubClient client,
      ObjectMapper objectMapper) {
    this(hubProperties, properties, client, objectMapper, Thread::sleep);
  }

  SeedanceCanvasFunctionAdapter(
      OpenCliHubProperties hubProperties,
      SeedanceCanvasProperties properties,
      OpenCliHubClient client,
      ObjectMapper objectMapper,
      Sleeper sleeper) {
    this.hubProperties = Objects.requireNonNull(hubProperties, "hubProperties");
    this.properties = Objects.requireNonNull(properties, "properties");
    properties.validate();
    this.client = Objects.requireNonNull(client, "client");
    uploader = new OpenCliReferenceUploader(client);
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  @Override
  public List<CanvasFunctionModel> models() {
    return MODELS;
  }

  @Override
  public boolean enabled() {
    return hubProperties.isEnabled()
        && properties.isEnabled()
        && properties.getWorkspaceId() != null;
  }

  @Override
  public String unavailableReason() {
    return enabled() ? null : "Seedance generation is disabled";
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    if (!MODEL_KEYS.contains(run.model().key())) {
      throw new IllegalArgumentException("unsupported Seedance model");
    }
    if (run.manifest().size() > 12) {
      throw new IllegalArgumentException("Seedance accepts at most 12 references");
    }
    long videoDuration = 0L;
    long audioDuration = 0L;
    int videoCount = 0;
    int audioCount = 0;
    for (CanvasFunctionFrozenReference reference : run.manifest()) {
      switch (reference.kind()) {
        case IMAGE -> {
          if (!reference.mediaType().toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException("Seedance IMAGE reference must use image/* MIME");
          }
        }
        case VIDEO -> {
          videoCount++;
          MediaMetadata metadata = parseMetadata(reference, true);
          if (!Set.of("mp4", "mov").contains(metadata.container())
              || !Set.of("h264", "hevc").contains(metadata.videoCodec())
              || (metadata.audioCodec() != null
                  && !Set.of("aac", "mp3").contains(metadata.audioCodec()))) {
            throw new IllegalArgumentException(
                "Seedance VIDEO requires MP4/MOV, h264/hevc and optional aac/mp3 audio");
          }
          validateDuration(metadata.durationMs(), "VIDEO");
          videoDuration += metadata.durationMs();
        }
        case AUDIO -> {
          audioCount++;
          MediaMetadata metadata = parseMetadata(reference, false);
          if (!Set.of("wav", "mp3").contains(metadata.container())
              || metadata.audioCodec() == null) {
            throw new IllegalArgumentException("Seedance AUDIO metadata is invalid");
          }
          validateDuration(metadata.durationMs(), "AUDIO");
          audioDuration += metadata.durationMs();
        }
        case TEXT -> throw new IllegalArgumentException("Seedance does not accept TEXT references");
      }
    }
    if (videoCount > 3 || audioCount > 3) {
      throw new IllegalArgumentException("Seedance accepts at most 3 VIDEO and 3 AUDIO references");
    }
    if (videoDuration > 15_000L) {
      throw new IllegalArgumentException(
          "Seedance VIDEO total duration must be at most 15 seconds");
    }
    if (audioDuration > 15_000L) {
      throw new IllegalArgumentException(
          "Seedance AUDIO total duration must be at most 15 seconds");
    }
  }

  @Override
  public List<Long> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    preflight(run);
    Map<String, Object> state = OpenCliAdapterState.copy(run.adapterState());
    String assetId = OpenCliAdapterState.optionalString(state, OpenCliAdapterState.ASSET_ID);
    String executionId =
        OpenCliAdapterState.optionalString(state, OpenCliAdapterState.EXECUTION_ID);
    if ("SEEDANCE_SUBMITTING".equals(run.stage()) && assetId == null && executionId == null) {
      throw new IllegalStateException(UNKNOWN_SUBMISSION);
    }

    List<OpenCliAdapterState.UploadedInput> uploads;
    if (assetId == null) {
      if (executionId == null) {
        OpenCliReferenceUploader.UploadResult uploadResult =
            uploader.uploadAll(context, run, state);
        uploads = uploadResult.uploads();
        state = uploadResult.state();
        context.checkpoint("SEEDANCE_SUBMITTING", state);
        Execution submitted =
            client.execute(
                buildSubmitArgv(run, uploads), properties.getHubExecutionTimeout().toMillis());
        executionId = submitted.id();
        state.put(OpenCliAdapterState.EXECUTION_ID, executionId);
        context.checkpoint("SEEDANCE_SUBMISSION_POLLING", state);
      } else {
        uploads = OpenCliAdapterState.uploads(state);
        requireCompleteUploads(run, uploads);
      }
      Execution submission =
          awaitHubExecution(context, executionId, properties.getHubExecutionTimeout());
      if (submission.status() != ExecutionStatus.SUCCEEDED) {
        throw new OpenCliHubException(
            "Seedance submission Hub execution ended as " + submission.status());
      }
      JsonNode submitted = singleStdoutObject(submission.stdout());
      if (!requiredBoolean(submitted, "submitted")
          || !"submitted".equals(requiredText(submitted, "status"))) {
        throw new OpenCliHubException("Seedance submission stdout did not confirm submitted=true");
      }
      assetId = requiredText(submitted, "assetId");
      if (!ASSET_ID.matcher(assetId).matches()) {
        throw new OpenCliHubException("Seedance submission stdout contains invalid assetId");
      }
      state.remove(OpenCliAdapterState.EXECUTION_ID);
      state.put(OpenCliAdapterState.ASSET_ID, assetId);
      state.put(OpenCliAdapterState.POLL_STARTED_AT, Instant.now().toString());
      context.checkpoint("SEEDANCE_STATUS_WAITING", state);
    } else {
      uploads = OpenCliAdapterState.uploads(state);
      requireCompleteUploads(run, uploads);
    }

    Instant deadline = pollDeadline(state);
    while (true) {
      if (!context.isRunning()) {
        throw new IllegalStateException("Canvas Function run is no longer running");
      }
      if (!Instant.now().isBefore(deadline)) {
        throw new OpenCliHubException("Seedance generation exceeded maximum wait");
      }
      executionId = OpenCliAdapterState.optionalString(state, OpenCliAdapterState.EXECUTION_ID);
      if (executionId == null) {
        sleep(properties.getStatusPollInterval());
        if (!context.isRunning()) {
          throw new IllegalStateException("Canvas Function run is no longer running");
        }
        if (!Instant.now().isBefore(deadline)) {
          throw new OpenCliHubException("Seedance generation exceeded maximum wait");
        }
        Execution statusExecution =
            client.execute(
                buildStatusArgv(assetId), properties.getHubExecutionTimeout().toMillis());
        executionId = statusExecution.id();
        state.put(OpenCliAdapterState.EXECUTION_ID, executionId);
        context.checkpoint("SEEDANCE_STATUS_POLLING", state);
      }
      Execution statusResult =
          awaitHubExecution(context, executionId, properties.getHubExecutionTimeout());
      if (statusResult.status() != ExecutionStatus.SUCCEEDED) {
        throw new OpenCliHubException(
            "Seedance status Hub execution ended as " + statusResult.status());
      }
      JsonNode row = singleStdoutObject(statusResult.stdout());
      String status = requiredText(row, "status");
      switch (status) {
        case "generating", "not_found" -> {
          state.remove(OpenCliAdapterState.EXECUTION_ID);
          context.checkpoint("SEEDANCE_STATUS_WAITING", state);
        }
        case "failed", "cancelled" -> throw new OpenCliHubException(
            "Seedance asset ended as " + status);
        case "ready" -> {
          if (!requiredBoolean(row, "downloaded")) {
            throw new OpenCliHubException("ready Seedance asset was not downloaded");
          }
          if (statusResult.resources().size() != 1
              || !statusResult
                  .resources()
                  .get(0)
                  .mimeType()
                  .toLowerCase(Locale.ROOT)
                  .startsWith("video/")) {
            throw new OpenCliHubException(
                "ready Seedance asset requires exactly one video Hub resource");
          }
          state.remove(OpenCliAdapterState.EXECUTION_ID);
          context.checkpoint("SEEDANCE_MATERIALIZING", state);
          ExecutionResource output = statusResult.resources().get(0);
          try (HubResourceStream stream = client.openResource(output)) {
            long resourceId =
                context.materializeTarget(
                    run.targetResourceId(), stream.mediaType(), stream.size(), stream.content());
            return List.of(resourceId);
          } catch (IOException exception) {
            throw new UncheckedIOException("failed to close Seedance Hub resource", exception);
          }
        }
        default -> throw new OpenCliHubException("unsupported Seedance asset status: " + status);
      }
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

  private List<String> buildSubmitArgv(
      CanvasFunctionFrozenRun run, List<OpenCliAdapterState.UploadedInput> uploads) {
    requireCompleteUploads(run, uploads);
    List<String> argv = new ArrayList<>();
    argv.addAll(
        List.of(
            "jimeng-agent",
            "video",
            "--workspace",
            properties.getWorkspaceId(),
            "--ratio",
            (String) run.config().parameters().get("ratio"),
            "--model_version",
            run.model().key(),
            "--duration",
            Integer.toString((Integer) run.config().parameters().get("duration"))));
    for (int index = 0; index < run.manifest().size(); index++) {
      argv.add(referenceFlag(run.manifest().get(index).kind()));
      argv.add(uploads.get(index).resourcePath());
    }
    argv.add("--prompt");
    argv.add(CanvasPromptRenderer.seedance(run));
    argv.add("--submit");
    argv.add("1");
    argv.add("--retry");
    argv.add(Integer.toString(properties.getRetry()));
    argv.add("--format");
    argv.add("json");
    return List.copyOf(argv);
  }

  private List<String> buildStatusArgv(String assetId) {
    return List.of(
        "jimeng-agent",
        "status",
        "--workspace",
        properties.getWorkspaceId(),
        "--search_key",
        assetId,
        "--download",
        "1",
        "--type",
        "video",
        "--limit",
        "1",
        "--max_pages",
        "5",
        "--format",
        "json");
  }

  private Execution awaitHubExecution(
      CanvasFunctionExecutionContext context, String executionId, Duration maxWait) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    Execution execution = client.getExecution(executionId, 0);
    while (!execution.status().terminal()) {
      if (!context.isRunning()) {
        throw new IllegalStateException("Canvas Function run is no longer running");
      }
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0L) {
        throw new OpenCliHubException("Seedance Hub execution exceeded maximum wait");
      }
      int waitSeconds =
          (int) Math.max(1L, Math.min(120L, Duration.ofNanos(remainingNanos).toSeconds()));
      execution = client.getExecution(executionId, waitSeconds);
    }
    return execution;
  }

  private JsonNode singleStdoutObject(String stdout) {
    try {
      JsonNode root = mapper.readTree(stdout);
      if (root != null && root.isObject()) {
        return root;
      }
      if (root instanceof ArrayNode array && array.size() == 1 && array.get(0).isObject()) {
        return array.get(0);
      }
      throw new OpenCliHubException(
          "Seedance stdout must be one object or a single-element object array");
    } catch (JsonProcessingException exception) {
      throw new OpenCliHubException("Seedance stdout must be valid JSON", exception);
    }
  }

  private MediaMetadata parseMetadata(CanvasFunctionFrozenReference reference, boolean video) {
    try {
      JsonNode root = mapper.readTree(reference.metadataJson());
      if (root == null || !root.isObject()) {
        throw new OpenCliHubException("Seedance media metadata must be an object");
      }
      String container = requiredText(root, "container").toLowerCase(Locale.ROOT);
      long durationMs = requiredLong(root, "durationMs");
      if (video) {
        String videoCodec = requiredText(root, "videoCodec").toLowerCase(Locale.ROOT);
        String audioCodec = optionalText(root, "audioCodec");
        return new MediaMetadata(
            container,
            videoCodec,
            audioCodec == null ? null : audioCodec.toLowerCase(Locale.ROOT),
            durationMs);
      }
      String codec = requiredText(root, "codec").toLowerCase(Locale.ROOT);
      return new MediaMetadata(container, null, codec, durationMs);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Seedance media metadata must be valid JSON", exception);
    }
  }

  private Instant pollDeadline(Map<String, Object> state) {
    String started = OpenCliAdapterState.optionalString(state, OpenCliAdapterState.POLL_STARTED_AT);
    if (started == null) {
      throw new IllegalArgumentException("Seedance checkpoint is missing pollStartedAt");
    }
    try {
      return Instant.parse(started).plus(properties.getMaxWait());
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("Seedance checkpoint pollStartedAt is invalid", exception);
    }
  }

  private void sleep(Duration interval) {
    try {
      sleeper.sleep(interval.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Seedance polling interrupted", exception);
    }
  }

  private static String referenceFlag(CanvasResourceKind kind) {
    return switch (kind) {
      case IMAGE -> "--image";
      case VIDEO -> "--video";
      case AUDIO -> "--audio";
      case TEXT -> throw new IllegalArgumentException("TEXT cannot be a Seedance reference");
    };
  }

  private static void requireCompleteUploads(
      CanvasFunctionFrozenRun run, List<OpenCliAdapterState.UploadedInput> uploads) {
    if (uploads.size() != run.manifest().size()) {
      throw new IllegalArgumentException("checkpoint uploads do not cover frozen manifest");
    }
    for (int index = 0; index < uploads.size(); index++) {
      if (uploads.get(index).resourceId() != run.manifest().get(index).resourceId()) {
        throw new IllegalArgumentException("checkpoint uploads do not match frozen manifest");
      }
    }
  }

  private static void validateDuration(long durationMs, String kind) {
    if (durationMs < 2000L || durationMs > 15_000L) {
      throw new IllegalArgumentException(kind + " duration must be between 2 and 15 seconds");
    }
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new OpenCliHubException(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new OpenCliHubException(field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static long requiredLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return value.longValue();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text or null");
    }
    return value.textValue();
  }

  private static CanvasFunctionModel model(String key, String label) {
    return new CanvasFunctionModel(
        key,
        label,
        CanvasResourceKind.VIDEO,
        new CanvasFunctionReferencePolicy(
            Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO, CanvasResourceKind.AUDIO),
            12,
            Map.of(CanvasResourceKind.VIDEO, 3, CanvasResourceKind.AUDIO, 3)),
        List.of(
            CanvasFunctionParameterDefinition.enumParameter(
                "ratio", "Ratio", false, "16:9", RATIOS),
            CanvasFunctionParameterDefinition.integerParameter(
                "duration", "Duration", false, 5, 4, 15)));
  }

  private record MediaMetadata(
      String container, String videoCodec, String audioCodec, long durationMs) {}

  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }
}
