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

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.UploadedResource;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adapter golden argv、checkpoint 恢复、provider 状态和流式 materialize 契约。 */
class OpenCliCanvasFunctionAdaptersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID NODE = new UUID(0L, 2L);
  private static final UUID REQUEST = new UUID(0L, 3L);
  private static final UUID TARGET = new UUID(0L, 900L);
  private static final UUID RESOURCE_100 = new UUID(0L, 100L);
  private static final UUID RESOURCE_101 = new UUID(0L, 101L);
  private static final UUID RESOURCE_102 = new UUID(0L, 102L);

  @Test
  void disabledAdaptersStillDeclareModelsWithoutLeakingConfiguration() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter gpt =
        new GptImage2CanvasFunctionAdapter(adapterSnapshot(), client);
    SeedanceCanvasFunctionAdapter seedance =
        new SeedanceCanvasFunctionAdapter(adapterSnapshot(), client, MAPPER, ignored -> {});

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
                new ReferenceSegment(new UUID(0L, 11), 0),
                new TextSegment(" behind "),
                new ReferenceSegment(new UUID(0L, 10), 0)),
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
    RecordingContext context =
        new RecordingContext(Map.of(RESOURCE_100, bytes(100), RESOURCE_101, bytes(101)));

    assertEquals(List.of(TARGET), adapter.execute(context, run));

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
    assertEquals(List.of(RESOURCE_100, RESOURCE_101), context.openedResourceIds);
    assertEquals("GPT_IMAGE_MATERIALIZING", context.stages.get(context.stages.size() - 1));
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
        List.of(
            Map.of(
                "resourceId",
                RESOURCE_100.toString(),
                "resourcePath",
                "/resources/date/upload/x.png")));
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("use "), new ReferenceSegment(new UUID(0L, 10), 0)),
            Map.of("ratio", "1:1"),
            List.of(image(10L, 0, 100L, "x.png")),
            "GPT_IMAGE_POLLING",
            state);

    assertEquals(List.of(TARGET), adapter.execute(new RecordingContext(Map.of()), run));
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
        List.of(
            Map.of(
                "resourceId",
                RESOURCE_100.toString(),
                "resourcePath",
                "/resources/safe/%2e%2e/outside.png")));
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("use "), new ReferenceSegment(new UUID(0L, 10), 0)),
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
        reference(11L, 0, 101L, CanvasResourceKind.VIDEO, "video.mp4", "video/mp4", 2000L);
    CanvasFunctionFrozenReference audio =
        reference(12L, 0, 102L, CanvasResourceKind.AUDIO, "audio.mp3", "audio/mpeg", 3000L);
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(1),
            List.of(
                new TextSegment("Start "),
                new ReferenceSegment(new UUID(0L, 11), 0),
                new TextSegment(" then "),
                new ReferenceSegment(new UUID(0L, 10), 0),
                new TextSegment(" with "),
                new ReferenceSegment(new UUID(0L, 12), 0)),
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
        new RecordingContext(
            Map.of(RESOURCE_100, bytes(1), RESOURCE_101, bytes(2), RESOURCE_102, bytes(3)));

    assertEquals(List.of(TARGET), adapter.execute(context, run));

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
    assertArrayEquals(new byte[] {4, 5, 6}, context.materialized);
  }

  @Test
  void seedancePreflightRejectsBadFormatDurationTotalsAndUnknownSubmittingOutcome() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
    CanvasFunctionModel model = adapter.models().get(0);
    CanvasFunctionFrozenReference badCodec =
        reference(10L, 0, 100L, CanvasResourceKind.VIDEO, "bad.webp", "video/webp", 2000L);
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
            reference(10L, 0, 100L, CanvasResourceKind.VIDEO, "a.mp4", "video/mp4", 8000L),
            reference(11L, 0, 101L, CanvasResourceKind.VIDEO, "b.mov", "video/quicktime", 8000L));
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

    assertEquals(List.of(TARGET), adapter.execute(new RecordingContext(Map.of()), run));
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

  @Test
  void gptRecoveryPollsToSuccessAndAllNonSuccessTerminalStatusesFailWithoutResubmission() {
    for (ExecutionStatus status :
        List.of(ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT, ExecutionStatus.CANCELLED)) {
      OpenCliHubClient client = mock(OpenCliHubClient.class);
      GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
      when(client.getExecution("existing", 0))
          .thenReturn(new Execution("existing", status, "", "", List.of()));
      CanvasFunctionFrozenRun run =
          run(
              adapter.models().get(0),
              List.of(new TextSegment("draw")),
              Map.of("ratio", "auto"),
              List.of(),
              "GPT_IMAGE_POLLING",
              Map.of("executionId", "existing", "uploads", List.of()));

      OpenCliHubException error =
          assertThrows(
              OpenCliHubException.class,
              () -> adapter.execute(new RecordingContext(Map.of()), run));
      assertTrue(error.getMessage().contains(status.name()));
      verify(client, never()).execute(any(), anyLong());
    }

    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    ExecutionResource output =
        new ExecutionResource("x.png", "image/png", 1L, null, "/api/resources/x.png");
    when(client.getExecution(anyString(), anyInt()))
        .thenAnswer(
            invocation ->
                invocation.<Integer>getArgument(1) == 0
                    ? new Execution("existing", ExecutionStatus.RUNNING, "", "", List.of())
                    : new Execution(
                        "existing", ExecutionStatus.SUCCEEDED, "", "", List.of(output)));
    when(client.openResource(output))
        .thenReturn(
            new HubResourceStream(new ByteArrayInputStream(new byte[] {1}), 1L, "image/png"));
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("draw")),
            Map.of("ratio", "auto"),
            List.of(),
            "GPT_IMAGE_POLLING",
            Map.of("executionId", "existing", "uploads", List.of()));

    assertEquals(List.of(TARGET), adapter.execute(new RecordingContext(Map.of()), run));
    verify(client).getExecution("existing", 120);

    CanvasFunctionFrozenRun noExecution =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("draw")),
            Map.of("ratio", "auto"),
            List.of(),
            "QUEUED",
            Map.of());
    adapter.cancel(noExecution);
    verify(client, never()).cancelPendingBestEffort(anyString());
  }

  @Test
  void seedanceSubmissionRecoveryUsesNotFoundAsRetryThenMaterializesReadyVideo() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
    when(client.getExecution("submit-existing", 0))
        .thenReturn(
            new Execution(
                "submit-existing",
                ExecutionStatus.SUCCEEDED,
                "{\"status\":\"submitted\",\"submitted\":true,"
                    + "\"assetId\":\"0123456789abcdef\"}",
                "",
                List.of()));
    when(client.execute(any(), anyLong()))
        .thenReturn(
            new Execution("status-1", ExecutionStatus.PENDING, "", "", List.of()),
            new Execution("status-2", ExecutionStatus.PENDING, "", "", List.of()));
    when(client.getExecution("status-1", 0))
        .thenReturn(
            new Execution(
                "status-1",
                ExecutionStatus.SUCCEEDED,
                "{\"status\":\"not_found\"}",
                "",
                List.of()));
    ExecutionResource output =
        new ExecutionResource("video.mp4", "video/mp4", 1L, null, "/api/resources/video.mp4");
    when(client.getExecution("status-2", 0))
        .thenReturn(
            new Execution(
                "status-2",
                ExecutionStatus.SUCCEEDED,
                "{\"status\":\"ready\",\"downloaded\":true}",
                "",
                List.of(output)));
    when(client.openResource(output))
        .thenReturn(
            new HubResourceStream(new ByteArrayInputStream(new byte[] {8}), 1L, "video/mp4"));
    CanvasFunctionFrozenRun run =
        run(
            adapter.models().get(0),
            List.of(new TextSegment("x")),
            Map.of("ratio", "16:9", "duration", 5),
            List.of(),
            "SEEDANCE_SUBMISSION_POLLING",
            Map.of("executionId", "submit-existing", "uploads", List.of()));
    RecordingContext context = new RecordingContext(Map.of());

    assertEquals(List.of(TARGET), adapter.execute(context, run));
    verify(client, times(2)).execute(any(), anyLong());
    assertTrue(context.stages.contains("SEEDANCE_STATUS_WAITING"));
    assertEquals("SEEDANCE_MATERIALIZING", context.stages.get(context.stages.size() - 1));
  }

  @Test
  void seedanceRejectsMalformedSubmissionAndStatusShapesAndHubTerminalFailures() {
    for (String stdout :
        List.of(
            "{\"status\":\"submitted\",\"submitted\":false," + "\"assetId\":\"0123456789abcdef\"}",
            "{\"status\":\"submitted\",\"submitted\":true,\"assetId\":\"bad\"}",
            "[]",
            "not-json")) {
      OpenCliHubClient client = mock(OpenCliHubClient.class);
      SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
      when(client.getExecution("submit-existing", 0))
          .thenReturn(
              new Execution("submit-existing", ExecutionStatus.SUCCEEDED, stdout, "", List.of()));
      CanvasFunctionFrozenRun run =
          run(
              adapter.models().get(0),
              List.of(new TextSegment("x")),
              Map.of("ratio", "16:9", "duration", 5),
              List.of(),
              "SEEDANCE_SUBMISSION_POLLING",
              Map.of("executionId", "submit-existing", "uploads", List.of()));
      assertThrows(
          OpenCliHubException.class, () -> adapter.execute(new RecordingContext(Map.of()), run));
      verify(client, never()).execute(any(), anyLong());
    }

    for (String stdout :
        List.of(
            "[]",
            "[{\"status\":\"ready\"},{\"status\":\"ready\"}]",
            "{}",
            "{\"status\":\"ready\",\"downloaded\":false}",
            "{\"status\":\"ready\",\"downloaded\":true}",
            "{\"status\":\"unknown\"}")) {
      OpenCliHubClient client = mock(OpenCliHubClient.class);
      SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
      when(client.getExecution("status-existing", 0))
          .thenReturn(
              new Execution("status-existing", ExecutionStatus.SUCCEEDED, stdout, "", List.of()));
      assertThrows(
          OpenCliHubException.class,
          () ->
              adapter.execute(
                  new RecordingContext(Map.of()),
                  seedanceStatusRun(adapter, "status-existing", Instant.now().toString())));
    }

    for (ExecutionStatus status :
        List.of(ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT, ExecutionStatus.CANCELLED)) {
      OpenCliHubClient client = mock(OpenCliHubClient.class);
      SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
      when(client.getExecution("status-existing", 0))
          .thenReturn(new Execution("status-existing", status, "", "", List.of()));
      assertThrows(
          OpenCliHubException.class,
          () ->
              adapter.execute(
                  new RecordingContext(Map.of()),
                  seedanceStatusRun(adapter, "status-existing", Instant.now().toString())));
    }
  }

  @Test
  void seedanceCheckpointDeadlineAndInterruptedSleepFailBeforeAnyStatusSubmission() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.execute(
                new RecordingContext(Map.of()), seedanceStatusRun(adapter, null, null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.execute(
                new RecordingContext(Map.of()),
                seedanceStatusRun(adapter, null, "not-an-instant")));

    SeedanceCanvasFunctionAdapter interrupted =
        seedanceAdapter(
            client,
            ignored -> {
              throw new InterruptedException("stop");
            });
    assertThrows(
        IllegalStateException.class,
        () ->
            interrupted.execute(
                new RecordingContext(Map.of()),
                seedanceStatusRun(interrupted, null, Instant.now().toString())));
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();
    verify(client, never()).execute(any(), anyLong());
  }

  @Test
  void adapterPreflightRejectsUnsupportedKindsMetadataAndCheckpointUploadMismatch() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter gpt = gptAdapter(client);
    CanvasFunctionFrozenReference video =
        reference(10L, 0, 100L, CanvasResourceKind.VIDEO, "video.mp4", "video/mp4", 2000L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            gpt.preflight(
                run(
                    gpt.models().get(0),
                    List.of(new TextSegment("x")),
                    Map.of("ratio", "auto"),
                    List.of(video),
                    "QUEUED",
                    Map.of())));

    SeedanceCanvasFunctionAdapter seedance = seedanceAdapter(client, ignored -> {});
    CanvasFunctionFrozenReference text =
        reference(11L, 0, 101L, CanvasResourceKind.TEXT, "text.txt", "text/plain", null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            seedance.preflight(
                run(
                    seedance.models().get(0),
                    List.of(new TextSegment("x")),
                    Map.of("ratio", "16:9", "duration", 5),
                    List.of(text),
                    "QUEUED",
                    Map.of())));
    CanvasFunctionFrozenReference malformedAudio =
        reference(12L, 0, 102L, CanvasResourceKind.AUDIO, "audio.wav", "audio/wav", null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            seedance.preflight(
                run(
                    seedance.models().get(0),
                    List.of(new TextSegment("x")),
                    Map.of("ratio", "16:9", "duration", 5),
                    List.of(malformedAudio),
                    "QUEUED",
                    Map.of())));

    Map<String, Object> mismatched = new LinkedHashMap<>();
    mismatched.put("executionId", "existing");
    mismatched.put(
        "uploads",
        List.of(
            Map.of(
                "resourceId", new UUID(0L, 999L).toString(), "resourcePath", "/resources/a.png")));
    CanvasFunctionFrozenRun recovery =
        run(
            gpt.models().get(0),
            List.of(new TextSegment("x")),
            Map.of("ratio", "auto"),
            List.of(image(10L, 0, 100L, "a.png")),
            "GPT_IMAGE_POLLING",
            mismatched);
    assertThrows(
        IllegalArgumentException.class,
        () -> gpt.execute(new RecordingContext(Map.of()), recovery));
    verify(client, never()).getExecution(anyString(), anyInt());
  }

  @Test
  void adaptersWrapMaterializedHubResourceCloseFailures() {
    InputStream closeFailure =
        new ByteArrayInputStream(new byte[] {1}) {
          @Override
          public void close() throws IOException {
            throw new IOException("close failed");
          }
        };
    OpenCliHubClient gptClient = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter gpt = gptAdapter(gptClient);
    ExecutionResource imageOutput =
        new ExecutionResource("x.png", "image/png", 1L, null, "/api/resources/x.png");
    when(gptClient.getExecution("gpt-existing", 0))
        .thenReturn(
            new Execution("gpt-existing", ExecutionStatus.SUCCEEDED, "", "", List.of(imageOutput)));
    when(gptClient.openResource(imageOutput))
        .thenReturn(new HubResourceStream(closeFailure, 1L, "image/png"));
    CanvasFunctionFrozenRun gptRun =
        run(
            gpt.models().get(0),
            List.of(new TextSegment("x")),
            Map.of("ratio", "auto"),
            List.of(),
            "GPT_IMAGE_POLLING",
            Map.of("executionId", "gpt-existing", "uploads", List.of()));
    assertThrows(
        UncheckedIOException.class, () -> gpt.execute(new RecordingContext(Map.of()), gptRun));

    InputStream videoCloseFailure =
        new ByteArrayInputStream(new byte[] {1}) {
          @Override
          public void close() throws IOException {
            throw new IOException("close failed");
          }
        };
    OpenCliHubClient seedanceClient = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter seedance = seedanceAdapter(seedanceClient, ignored -> {});
    ExecutionResource videoOutput =
        new ExecutionResource("x.mp4", "video/mp4", 1L, null, "/api/resources/x.mp4");
    when(seedanceClient.getExecution("seedance-existing", 0))
        .thenReturn(
            new Execution(
                "seedance-existing",
                ExecutionStatus.SUCCEEDED,
                "{\"status\":\"ready\",\"downloaded\":true}",
                "",
                List.of(videoOutput)));
    when(seedanceClient.openResource(videoOutput))
        .thenReturn(new HubResourceStream(videoCloseFailure, 1L, "video/mp4"));
    assertThrows(
        UncheckedIOException.class,
        () ->
            seedance.execute(
                new RecordingContext(Map.of()),
                seedanceStatusRun(seedance, "seedance-existing", Instant.now().toString())));
  }

  private static CanvasFunctionFrozenRun seedanceStatusRun(
      SeedanceCanvasFunctionAdapter adapter, String executionId, String pollStartedAt) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("uploads", List.of());
    state.put("assetId", "0123456789abcdef");
    if (executionId != null) {
      state.put("executionId", executionId);
    }
    if (pollStartedAt != null) {
      state.put("pollStartedAt", pollStartedAt);
    }
    return run(
        adapter.models().get(0),
        List.of(new TextSegment("x")),
        Map.of("ratio", "16:9", "duration", 5),
        List.of(),
        executionId == null ? "SEEDANCE_STATUS_WAITING" : "SEEDANCE_STATUS_POLLING",
        state);
  }

  private static void assertNoHubManagedArguments(List<String> argv) {
    for (String token : List.of("--format", "-f", "--profile", "--op")) {
      assertFalse(argv.contains(token), () -> "Hub-managed argument must be absent: " + token);
    }
  }

  private static GptImage2CanvasFunctionAdapter gptAdapter(OpenCliHubClient client) {
    SystemSettings.GptImage2 gptImage2 =
        new SystemSettings.GptImage2(true, 900, 960_000L, 1_200_000L);
    return new GptImage2CanvasFunctionAdapter(adapterSnapshot(gptImage2), client);
  }

  private static SeedanceCanvasFunctionAdapter seedanceAdapter(
      OpenCliHubClient client, SeedanceCanvasFunctionAdapter.Sleeper sleeper) {
    SystemSettings.Seedance seedance =
        new SystemSettings.Seedance(true, "workspace-1", 2, 600_000L, 5L, 1_800_000L);
    return new SeedanceCanvasFunctionAdapter(adapterSnapshot(seedance), client, MAPPER, sleeper);
  }

  private static SystemSettingsSnapshot adapterSnapshot() {
    return adapterSnapshot(SystemSettings.Seedance.DEFAULT, SystemSettings.GptImage2.DEFAULT);
  }

  private static SystemSettingsSnapshot adapterSnapshot(SystemSettings.Seedance seedance) {
    return adapterSnapshot(seedance, SystemSettings.GptImage2.DEFAULT);
  }

  private static SystemSettingsSnapshot adapterSnapshot(SystemSettings.GptImage2 gptImage2) {
    return adapterSnapshot(SystemSettings.Seedance.DEFAULT, gptImage2);
  }

  private static SystemSettingsSnapshot adapterSnapshot(
      SystemSettings.Seedance seedance, SystemSettings.GptImage2 gptImage2) {
    return new SystemSettingsSnapshot(
        new SystemSettings(
            SystemSettings.Tool.DEFAULT,
            SystemSettings.AiRuntime.DEFAULT,
            SystemSettings.Environment.DEFAULT,
            new SystemSettings.Integrations(
                SystemSettings.Comfyui.DEFAULT,
                SystemSettings.OpenCliHub.DEFAULT,
                seedance,
                gptImage2,
                SystemSettings.MiniMaxH3.DEFAULT),
            SystemSettings.StorageMedia.DEFAULT,
            SystemSettings.Advanced.DEFAULT));
  }

  private static CanvasFunctionFrozenRun run(
      CanvasFunctionModel model,
      List<CanvasFunctionConfig.PromptSegment> segments,
      Map<String, Object> parameters,
      List<CanvasFunctionFrozenReference> manifest,
      String stage,
      Map<String, Object> state) {
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "output",
        REQUEST,
        model,
        new CanvasFunctionConfig(segments, parameters),
        manifest,
        model.outputKind() == CanvasResourceKind.IMAGE ? "output.png" : "output.mp4",
        TARGET,
        stage,
        state);
  }

  private static CanvasFunctionFrozenReference image(
      long nodeId, int index, long resourceId, String name) {
    return reference(nodeId, index, resourceId, CanvasResourceKind.IMAGE, name, "image/png", null);
  }

  private static CanvasFunctionFrozenReference reference(
      long nodeId,
      int index,
      long resourceId,
      CanvasResourceKind kind,
      String name,
      String mediaType,
      Long durationMs) {
    UUID resource = new UUID(0L, resourceId);
    return new CanvasFunctionFrozenReference(
        new UUID(0L, nodeId),
        index,
        resource,
        resource,
        kind,
        name,
        mediaType,
        3L,
        16L,
        16L,
        durationMs);
  }

  private static byte[] bytes(int value) {
    return new byte[] {(byte) value, (byte) (value + 1), (byte) (value + 2)};
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {

    private final Map<UUID, byte[]> originals;
    private final List<UUID> openedResourceIds = new ArrayList<>();
    private final List<String> stages = new ArrayList<>();
    private byte[] materialized;
    private boolean running = true;

    private RecordingContext(Map<UUID, byte[]> originals) {
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
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID materializeTarget(UUID targetResourceId, InputStream content) {
      try {
        materialized = content.readAllBytes();
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
      return targetResourceId;
    }
  }
}
