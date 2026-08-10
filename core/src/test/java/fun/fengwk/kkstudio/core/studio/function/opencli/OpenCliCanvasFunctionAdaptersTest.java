package fun.fengwk.kkstudio.core.studio.function.opencli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.UploadedResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapter golden argv、checkpoint 恢复、provider 状态和流式 materialize 契约。 */
class OpenCliCanvasFunctionAdaptersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void disabledAdaptersStillDeclareModelsWithoutLeakingConfiguration() {
    OpenCliHubProperties hub = new OpenCliHubProperties();
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter gpt =
        new GptImage2CanvasFunctionAdapter(hub, new GptImage2CanvasProperties(), client);
    SeedanceCanvasFunctionAdapter seedance =
        new SeedanceCanvasFunctionAdapter(
            hub, new SeedanceCanvasProperties(), client, MAPPER, ignored -> {});

    assertFalse(gpt.enabled());
    assertEquals(1, gpt.models().size());
    assertEquals("GPT Image 2 generation is disabled", gpt.unavailableReason());
    assertFalse(seedance.enabled());
    assertEquals(4, seedance.models().size());
    assertEquals("Seedance generation is disabled", seedance.unavailableReason());
  }

  @Test
  void gptImageUploadsInManifestOrderRendersGoldenArgvAndMaterializesExactlyOneImage() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    CanvasFunctionModel descriptor = adapter.models().get(0);
    assertEquals("gpt-image-2", descriptor.key());
    assertEquals("GPT Image 2", descriptor.label());
    assertEquals(CanvasResourceKind.IMAGE, descriptor.outputKind());
    assertEquals(20, descriptor.referencePolicy().maxReferences());
    assertEquals(
        List.of("auto", "1:1", "3:4", "9:16", "4:3", "16:9"),
        descriptor.parameters().get(0).options());
    assertEquals("auto", descriptor.parameters().get(0).defaultValue());
    CanvasFunctionFrozenReference first = image(10L, 0, 100L, "first.png");
    CanvasFunctionFrozenReference second = image(11L, 0, 101L, "second.png");
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(
                new TextSegment("Put "),
                new ReferenceSegment(11L, 0),
                new TextSegment(" behind "),
                new ReferenceSegment(10L, 0)),
            Map.of("ratio", "16:9"),
            List.of(first, second),
            "QUEUED",
            Map.of());
    when(client.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(
            new UploadedResource("/resources/date/upload/first.png"),
            new UploadedResource("/resources/date/upload/second.png"));
    ExecutionResource output =
        new ExecutionResource(
            "output.png", "image/png", 4L, null, "/api/resources/date/execution/output.png");
    Execution succeeded =
        new Execution("gpt-exec", ExecutionStatus.SUCCEEDED, "", "", List.of(output));
    when(client.execute(any(), anyLong())).thenReturn(succeeded);
    when(client.getExecution("gpt-exec", 0)).thenReturn(succeeded);
    when(client.openResource(output))
        .thenReturn(
            new HubResourceStream(
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 4L, "image/png"));
    RecordingContext context = new RecordingContext(Map.of(100L, bytes(100), 101L, bytes(101)));

    assertEquals(List.of(900L), adapter.execute(context, run));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
    verify(client).execute(argv.capture(), anyLong());
    List<String> tokens = argv.getValue();
    assertEquals("chatgpt-agent", tokens.get(0));
    assertEquals("ask", tokens.get(1));
    assertTrue(tokens.get(2).contains("Use GPT Image 2"));
    assertTrue(tokens.get(2).contains("exactly one image"));
    assertTrue(tokens.get(2).contains("Put [Reference image 2] behind [Reference image 1]"));
    assertEquals(
        List.of(
            "--file",
            "/resources/date/upload/first.png",
            "--file",
            "/resources/date/upload/second.png",
            "--timeout",
            "900"),
        tokens.subList(3, tokens.size()));
    assertNoHubManagedArguments(tokens);
    assertEquals(List.of(100L, 101L), context.openedResourceIds);
    assertEquals("GPT_IMAGE_MATERIALIZING", context.stages.get(context.stages.size() - 1));
    assertEquals("image/png", context.materializedMediaType);
    assertArrayEquals(new byte[] {1, 2, 3, 4}, context.materialized);
  }

  @Test
  void gptImageRefusesUnknownSubmittingOutcomeAndNonSingleImageOutput() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    CanvasFunctionFrozenRun unknown =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("draw")),
            Map.of("ratio", "auto"),
            List.of(),
            "GPT_IMAGE_SUBMITTING",
            Map.of());
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> adapter.execute(new RecordingContext(Map.of()), unknown));
    assertTrue(error.getMessage().contains("outcome unknown"));
    verify(client, never()).execute(any(), anyLong());

    OpenCliHubClient secondClient = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter secondAdapter = gptAdapter(secondClient);
    CanvasFunctionFrozenRun run =
        run(
            secondAdapter.models().get(0),
            List.of(new TextSegment("draw")),
            Map.of("ratio", "auto"),
            List.of(),
            "QUEUED",
            Map.of());
    Execution bad =
        new Execution(
            "bad",
            ExecutionStatus.SUCCEEDED,
            "",
            "",
            List.of(
                new ExecutionResource("a.png", "image/png", 1L, null, "/api/resources/a"),
                new ExecutionResource("b.png", "image/png", 1L, null, "/api/resources/b")));
    when(secondClient.execute(any(), anyLong())).thenReturn(bad);
    when(secondClient.getExecution("bad", 0)).thenReturn(bad);
    assertThrows(
        OpenCliHubException.class,
        () -> secondAdapter.execute(new RecordingContext(Map.of()), run));
  }

  @Test
  void gptImageRecoveryReusesUploadsAndExecutionAndCancelIsBestEffortPendingOnly() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    ExecutionResource output =
        new ExecutionResource("x.png", "image/png", 1L, null, "/api/resources/x.png");
    Execution success =
        new Execution("existing", ExecutionStatus.SUCCEEDED, "", "", List.of(output));
    when(client.getExecution("existing", 0)).thenReturn(success);
    when(client.openResource(output))
        .thenReturn(
            new HubResourceStream(new ByteArrayInputStream(new byte[] {7}), 1L, "image/png"));
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("executionId", "existing");
    state.put(
        "uploads",
        List.of(Map.of("resourceId", "100", "resourcePath", "/resources/date/upload/x.png")));
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("use "), new ReferenceSegment(10L, 0)),
            Map.of("ratio", "1:1"),
            List.of(image(10L, 0, 100L, "x.png")),
            "GPT_IMAGE_POLLING",
            state);

    assertEquals(List.of(900L), adapter.execute(new RecordingContext(Map.of()), run));
    verify(client, never()).upload(anyString(), anyString(), anyLong(), any(InputStream.class));
    verify(client, never()).execute(any(), anyLong());
    adapter.cancel(run);
    verify(client).cancelPendingBestEffort("existing");
  }

  @Test
  void gptImageRecoveryRejectsDecodedDotSegmentsInCheckpointResourcePath() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("executionId", "existing");
    state.put(
        "uploads",
        List.of(Map.of("resourceId", "100", "resourcePath", "/resources/safe/%2e%2e/outside.png")));
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("use "), new ReferenceSegment(10L, 0)),
            Map.of("ratio", "1:1"),
            List.of(image(10L, 0, 100L, "x.png")),
            "GPT_IMAGE_POLLING",
            state);

    assertThrows(
        IllegalArgumentException.class, () -> adapter.execute(new RecordingContext(Map.of()), run));
    verify(client, never()).getExecution(anyString(), anyInt());
  }

  @Test
  void seedanceUsesExactModelsMetadataPromptArgvAndNonTightStatusPolling() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    List<Long> sleeps = new ArrayList<>();
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, sleeps::add);
    assertEquals(
        List.of("seedance2.0", "seedance2.0fast", "seedance2.0_vip", "seedance2.0fast_vip"),
        adapter.models().stream().map(CanvasFunctionModel::key).toList());
    assertTrue(
        adapter.models().stream()
            .allMatch(model -> model.outputKind() == CanvasResourceKind.VIDEO));
    assertEquals(
        List.of("1:1", "3:4", "16:9", "4:3", "9:16", "21:9"),
        adapter.models().get(0).parameters().get(0).options());
    assertEquals(5, adapter.models().get(0).parameters().get(1).defaultValue());

    CanvasFunctionFrozenReference image = image(10L, 0, 100L, "image.png");
    CanvasFunctionFrozenReference video =
        reference(
            11L,
            0,
            101L,
            CanvasResourceKind.VIDEO,
            "video.mp4",
            "video/mp4",
            """
            {"container":"mp4","videoCodec":"h264","audioCodec":"aac","durationMs":2000}
            """);
    CanvasFunctionFrozenReference audio =
        reference(
            12L,
            0,
            102L,
            CanvasResourceKind.AUDIO,
            "audio.mp3",
            "audio/mpeg",
            """
            {"container":"mp3","codec":"mp3","durationMs":3000}
            """);
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(1),
            List.of(
                new TextSegment("Start "),
                new ReferenceSegment(11L, 0),
                new TextSegment(" then "),
                new ReferenceSegment(10L, 0),
                new TextSegment(" with "),
                new ReferenceSegment(12L, 0)),
            Map.of("ratio", "9:16", "duration", 4),
            List.of(image, video, audio),
            "QUEUED",
            Map.of());
    when(client.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(
            new UploadedResource("/resources/u/image.png"),
            new UploadedResource("/resources/u/video.mp4"),
            new UploadedResource("/resources/u/audio.mp3"));
    when(client.execute(any(), anyLong()))
        .thenReturn(
            new Execution("submit", ExecutionStatus.PENDING, "", "", List.of()),
            new Execution("status-1", ExecutionStatus.PENDING, "", "", List.of()),
            new Execution("status-2", ExecutionStatus.PENDING, "", "", List.of()));
    when(client.getExecution("submit", 0))
        .thenReturn(
            new Execution(
                "submit",
                ExecutionStatus.SUCCEEDED,
                """
                {"status":"submitted","submitted":true,"assetId":"0123456789abcdef"}
                """,
                "",
                List.of()));
    when(client.getExecution("status-1", 0))
        .thenReturn(
            new Execution(
                "status-1",
                ExecutionStatus.SUCCEEDED,
                """
                [{"status":"generating","downloaded":false}]
                """,
                "",
                List.of()));
    ExecutionResource output =
        new ExecutionResource("video.mp4", "video/mp4", 3L, null, "/api/resources/video.mp4");
    when(client.getExecution("status-2", 0))
        .thenReturn(
            new Execution(
                "status-2",
                ExecutionStatus.SUCCEEDED,
                """
                [{"status":"ready","downloaded":true}]
                """,
                "",
                List.of(output)));
    when(client.openResource(output))
        .thenReturn(
            new HubResourceStream(new ByteArrayInputStream(new byte[] {4, 5, 6}), 3L, "video/mp4"));
    RecordingContext context =
        new RecordingContext(Map.of(100L, bytes(1), 101L, bytes(2), 102L, bytes(3)));

    assertEquals(List.of(900L), adapter.execute(context, run));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
    verify(client, times(3)).execute(argv.capture(), anyLong());
    assertEquals(
        List.of(
            "jimeng-agent",
            "video",
            "--workspace",
            "workspace-1",
            "--ratio",
            "9:16",
            "--model_version",
            "seedance2.0fast",
            "--duration",
            "4",
            "--image",
            "/resources/u/image.png",
            "--video",
            "/resources/u/video.mp4",
            "--audio",
            "/resources/u/audio.mp3",
            "--prompt",
            "Start @视频1 then @图片1 with @音频1",
            "--submit",
            "1",
            "--retry",
            "2"),
        argv.getAllValues().get(0));
    List<String> expectedStatus =
        List.of(
            "jimeng-agent",
            "status",
            "--workspace",
            "workspace-1",
            "--search_key",
            "0123456789abcdef",
            "--download",
            "1",
            "--type",
            "video",
            "--limit",
            "1",
            "--max_pages",
            "5");
    assertEquals(expectedStatus, argv.getAllValues().get(1));
    assertEquals(expectedStatus, argv.getAllValues().get(2));
    argv.getAllValues().forEach(OpenCliCanvasFunctionAdaptersTest::assertNoHubManagedArguments);
    assertEquals(List.of(5L, 5L), sleeps);
    assertEquals("video/mp4", context.materializedMediaType);
    assertArrayEquals(new byte[] {4, 5, 6}, context.materialized);
  }

  @Test
  void seedancePreflightRejectsBadFormatDurationTotalsAndUnknownSubmittingOutcome() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
    CanvasFunctionModel model = adapter.models().get(0);
    CanvasFunctionFrozenReference badCodec =
        reference(
            10L,
            0,
            100L,
            CanvasResourceKind.VIDEO,
            "bad.mp4",
            "video/mp4",
            """
            {"container":"mp4","videoCodec":"vp9","audioCodec":null,"durationMs":2000}
            """);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.preflight(
                run(
                    model,
                    List.of(new TextSegment("x")),
                    Map.of("ratio", "16:9", "duration", 5),
                    List.of(badCodec),
                    "QUEUED",
                    Map.of())));

    List<CanvasFunctionFrozenReference> tooLong =
        List.of(
            reference(
                10L,
                0,
                100L,
                CanvasResourceKind.VIDEO,
                "a.mp4",
                "video/mp4",
                "{\"container\":\"mp4\",\"videoCodec\":\"h264\",\"audioCodec\":null,\"durationMs\":8000}"),
            reference(
                11L,
                0,
                101L,
                CanvasResourceKind.VIDEO,
                "b.mov",
                "video/quicktime",
                "{\"container\":\"mov\",\"videoCodec\":\"hevc\",\"audioCodec\":\"aac\",\"durationMs\":8000}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.preflight(
                run(
                    model,
                    List.of(new TextSegment("x")),
                    Map.of("ratio", "16:9", "duration", 5),
                    tooLong,
                    "QUEUED",
                    Map.of())));

    CanvasFunctionFrozenRun unknown =
        run(
            model,
            List.of(new TextSegment("x")),
            Map.of("ratio", "16:9", "duration", 5),
            List.of(),
            "SEEDANCE_SUBMITTING",
            Map.of());
    assertThrows(
        IllegalStateException.class,
        () -> adapter.execute(new RecordingContext(Map.of()), unknown));
    verify(client, never()).execute(any(), anyLong());

    List<CanvasFunctionFrozenReference> tooManyImages = new ArrayList<>();
    for (int index = 0; index < 13; index++) {
      tooManyImages.add(image(100L + index, 0, 200L + index, "image-" + index + ".png"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.preflight(
                run(
                    model,
                    List.of(new TextSegment("x")),
                    Map.of("ratio", "16:9", "duration", 5),
                    tooManyImages,
                    "QUEUED",
                    Map.of())));
  }

  @Test
  void seedanceRecoveryPollsExistingExecutionWithoutResubmissionAndCancelUsesCurrentExecution() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    List<Long> sleeps = new ArrayList<>();
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, sleeps::add);
    ExecutionResource output =
        new ExecutionResource("video.mp4", "video/mp4", 1L, null, "/api/resources/video.mp4");
    when(client.getExecution("status-existing", 0))
        .thenReturn(
            new Execution(
                "status-existing",
                ExecutionStatus.SUCCEEDED,
                "{\"status\":\"ready\",\"downloaded\":true}",
                "",
                List.of(output)));
    when(client.openResource(output))
        .thenReturn(
            new HubResourceStream(new ByteArrayInputStream(new byte[] {9}), 1L, "video/mp4"));
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("uploads", List.of());
    state.put("assetId", "0123456789abcdef");
    state.put("executionId", "status-existing");
    state.put("pollStartedAt", Instant.now().toString());
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("x")),
            Map.of("ratio", "16:9", "duration", 5),
            List.of(),
            "SEEDANCE_STATUS_POLLING",
            state);

    assertEquals(List.of(900L), adapter.execute(new RecordingContext(Map.of()), run));
    assertTrue(sleeps.isEmpty());
    verify(client, never()).execute(any(), anyLong());
    adapter.cancel(run);
    verify(client).cancelPendingBestEffort("status-existing");
  }

  @Test
  void seedanceFailedAndCancelledAssetStatusesAreTerminalErrors() {
    for (String assetStatus : List.of("failed", "cancelled")) {
      OpenCliHubClient client = mock(OpenCliHubClient.class);
      SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
      String executionId = "status-" + assetStatus;
      when(client.getExecution(executionId, 0))
          .thenReturn(
              new Execution(
                  executionId,
                  ExecutionStatus.SUCCEEDED,
                  "{\"status\":\"" + assetStatus + "\",\"downloaded\":false}",
                  "",
                  List.of()));
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("uploads", List.of());
      state.put("assetId", "0123456789abcdef");
      state.put("executionId", executionId);
      state.put("pollStartedAt", Instant.now().toString());
      CanvasFunctionFrozenRun run =
          run(
              adapter.models().get(0),
              List.of(new TextSegment("x")),
              Map.of("ratio", "16:9", "duration", 5),
              List.of(),
              "SEEDANCE_STATUS_POLLING",
              state);

      OpenCliHubException error =
          assertThrows(
              OpenCliHubException.class,
              () -> adapter.execute(new RecordingContext(Map.of()), run));
      assertTrue(error.getMessage().contains(assetStatus));
    }
  }

  @Test
  void seedanceDoesNotStartStatusExecutionWhenRunStopsDuringPollSleep() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    RecordingContext context = new RecordingContext(Map.of());
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> context.stop());
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("uploads", List.of());
    state.put("assetId", "0123456789abcdef");
    state.put("pollStartedAt", Instant.now().toString());
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("x")),
            Map.of("ratio", "16:9", "duration", 5),
            List.of(),
            "SEEDANCE_STATUS_WAITING",
            state);

    assertThrows(IllegalStateException.class, () -> adapter.execute(context, run));
    verify(client, never()).execute(any(), anyLong());
  }

  private static void assertNoHubManagedArguments(List<String> argv) {
    for (String token : List.of("--format", "-f", "--profile", "--op")) {
      assertFalse(argv.contains(token), () -> "Hub-managed argument must be absent: " + token);
    }
  }

  private static GptImage2CanvasFunctionAdapter gptAdapter(OpenCliHubClient client) {
    OpenCliHubProperties hub = new OpenCliHubProperties();
    hub.setEnabled(true);
    GptImage2CanvasProperties properties = new GptImage2CanvasProperties();
    properties.setPaidEnabled(true);
    return new GptImage2CanvasFunctionAdapter(hub, properties, client);
  }

  private static SeedanceCanvasFunctionAdapter seedanceAdapter(
      OpenCliHubClient client, SeedanceCanvasFunctionAdapter.Sleeper sleeper) {
    OpenCliHubProperties hub = new OpenCliHubProperties();
    hub.setEnabled(true);
    SeedanceCanvasProperties properties = new SeedanceCanvasProperties();
    properties.setEnabled(true);
    properties.setWorkspaceId("workspace-1");
    properties.setRetry(2);
    properties.setStatusPollInterval(Duration.ofMillis(5));
    return new SeedanceCanvasFunctionAdapter(hub, properties, client, MAPPER, sleeper);
  }

  private static CanvasFunctionFrozenRun run(
      CanvasFunctionModel model,
      List<CanvasFunctionConfig.PromptSegment> segments,
      Map<String, Object> parameters,
      List<CanvasFunctionFrozenReference> manifest,
      String stage,
      Map<String, Object> state) {
    return new CanvasFunctionFrozenRun(
        1L,
        2L,
        "output",
        "request",
        model,
        new CanvasFunctionConfig(segments, parameters),
        manifest,
        model.outputKind() == CanvasResourceKind.IMAGE ? "output.png" : "output.mp4",
        900L,
        stage,
        state);
  }

  private static CanvasFunctionFrozenReference image(
      long nodeId, int index, long resourceId, String name) {
    return reference(
        nodeId,
        index,
        resourceId,
        CanvasResourceKind.IMAGE,
        name,
        "image/png",
        "{\"container\":\"png\",\"codec\":\"png\",\"width\":16,\"height\":16}");
  }

  private static CanvasFunctionFrozenReference reference(
      long nodeId,
      int index,
      long resourceId,
      CanvasResourceKind kind,
      String name,
      String mediaType,
      String metadata) {
    return new CanvasFunctionFrozenReference(
        nodeId, index, resourceId, kind, name, mediaType, 3L, metadata.strip());
  }

  private static byte[] bytes(int value) {
    return new byte[] {(byte) value, (byte) (value + 1), (byte) (value + 2)};
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {

    private final Map<Long, byte[]> originals;
    private final List<Long> openedResourceIds = new ArrayList<>();
    private final List<String> stages = new ArrayList<>();
    private byte[] materialized;
    private String materializedMediaType;
    private boolean running = true;

    private RecordingContext(Map<Long, byte[]> originals) {
      this.originals = originals;
    }

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      stages.add(stage);
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    private void stop() {
      running = false;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      openedResourceIds.add(reference.resourceId());
      byte[] bytes = originals.get(reference.resourceId());
      if (bytes == null) {
        throw new AssertionError("unexpected original " + reference.resourceId());
      }
      return new CanvasFunctionResourceStream(
          new ByteArrayInputStream(bytes), bytes.length, () -> {});
    }

    @Override
    public long materializeTarget(
        long targetResourceId, String mediaType, long size, InputStream content) {
      try {
        materialized = content.readAllBytes();
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
      assertEquals(size, materialized.length);
      materializedMediaType = mediaType;
      return targetResourceId;
    }
  }
}
