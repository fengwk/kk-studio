package fun.fengwk.kkstudio.plugin.opencli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonBool;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.UploadedResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Adapter golden argv、checkpoint 恢复、provider 状态和流式 materialize 契约。 */
class OpenCliCanvasFunctionAdaptersTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID NODE = new UUID(0L, 2L);
  private static final UUID REQUEST = new UUID(0L, 3L);
  private static final UUID TARGET = new UUID(0L, 900L);
  private static final UUID RESOURCE_100 = new UUID(0L, 100L);
  private static final UUID RESOURCE_101 = new UUID(0L, 101L);
  private static final UUID RESOURCE_102 = new UUID(0L, 102L);

  @Test
  void disabledAdaptersStillDeclareFunctionsWithoutLeakingConfiguration() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter gpt =
        new GptImage2CanvasFunctionAdapter(OpenCliTestSettings.defaults(), client);
    SeedanceCanvasFunctionAdapter seedance =
        new SeedanceCanvasFunctionAdapter(OpenCliTestSettings.defaults(), client, MAPPER, ignored -> {});

    assertFalse(gpt.enabled());
    assertEquals(1, gpt.functions().size());
    assertEquals("GPT Image 2 generation is disabled", gpt.unavailableReason());
    assertFalse(seedance.enabled());
    assertEquals(4, seedance.functions().size());
    assertEquals("Seedance generation is disabled", seedance.unavailableReason());

    OpenCliSettings globalDisabled =
        OpenCliTestSettings.parse(
            "{\"gptImage2\":{\"paidEnabled\":true},"
                + "\"seedance\":{\"enabled\":true,\"workspaceId\":\"workspace-1\",\"retry\":2}}");
    GptImage2CanvasFunctionAdapter gptGlobalDisabled =
        new GptImage2CanvasFunctionAdapter(globalDisabled, client);
    SeedanceCanvasFunctionAdapter seedanceGlobalDisabled =
        new SeedanceCanvasFunctionAdapter(globalDisabled, client, MAPPER, ignored -> {});
    assertFalse(gptGlobalDisabled.enabled());
    assertEquals("GPT Image 2 generation is disabled", gptGlobalDisabled.unavailableReason());
    assertFalse(seedanceGlobalDisabled.enabled());
    assertEquals("Seedance generation is disabled", seedanceGlobalDisabled.unavailableReason());

    OpenCliSettings seedanceDisabled =
        OpenCliTestSettings.parse(
            "{\"enabled\":true,\"baseUrl\":\"http://hub.local\","
                + "\"gptImage2\":{\"paidEnabled\":true}}");
    SeedanceCanvasFunctionAdapter seedanceDisabledAdapter =
        new SeedanceCanvasFunctionAdapter(seedanceDisabled, client, MAPPER, ignored -> {});
    assertFalse(seedanceDisabledAdapter.enabled());
    assertEquals("Seedance generation is disabled", seedanceDisabledAdapter.unavailableReason());

    GptImage2CanvasFunctionAdapter gptEnabled = gptAdapter(client);
    SeedanceCanvasFunctionAdapter seedanceEnabled = seedanceAdapter(client, ignored -> {});
    assertTrue(gptEnabled.enabled());
    assertNull(gptEnabled.unavailableReason());
    assertTrue(seedanceEnabled.enabled());
    assertNull(seedanceEnabled.unavailableReason());

    SeedanceCanvasFunctionAdapter twoArgSeedance =
        new SeedanceCanvasFunctionAdapter(OpenCliTestSettings.defaults(), client);
    assertFalse(twoArgSeedance.enabled());
    SeedanceCanvasFunctionAdapter fourArgSeedance =
        new SeedanceCanvasFunctionAdapter(OpenCliTestSettings.defaults(), client, MAPPER, Clock.systemUTC());
    assertFalse(fourArgSeedance.enabled());
  }

  @Test
  void gptImageUploadsInManifestOrderRendersGoldenArgvAndMaterializesExactlyOneImage() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    CanvasFunctionDefinition definition = adapter.functions().get(0);
    assertEquals("gpt-image-2", definition.name());
    assertEquals("GPT Image 2", definition.description());
    assertEquals(
        List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)), definition.outputs());
    assertEquals(20, definition.referencePolicy().maxReferences());

    CanvasFunctionFrozenReference first = image(10L, 0, 100L, "first.png");
    CanvasFunctionFrozenReference second = image(11L, 0, 101L, "second.png");
    CanvasFunctionFrozenRun run =
        run(
            definition,
            "Put [Reference image 2] behind [Reference image 1]",
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

    adapter.submit(context, run);
    Map<String, Object> submittedState =
        Map.of("executionId", "gpt-exec", "uploads", context.lastAdapterState.get("uploads"));
    CanvasFunctionFrozenRun submittedRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            binding(definition),
            run.args(),
            run.manifest(),
            run.outputs(),
            CanvasFunctionSubmitState.SUBMITTED,
            "GPT_IMAGE_POLLING",
            submittedState);

    assertEquals(List.of(TARGET), adapter.execute(context, submittedRun));

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
            adapter.functions().get(0),
            "draw",
            Map.of("ratio", "auto"),
            List.of(),
            "GPT_IMAGE_SUBMITTING",
            Map.of());
    CanvasFunctionUnknownException error =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () -> adapter.execute(new RecordingContext(Map.of()), unknown));
    assertTrue(error.getMessage().contains("outcome unknown"));
    verify(client, never()).execute(any(), anyLong());

    OpenCliHubClient secondClient = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter secondAdapter = gptAdapter(secondClient);
    CanvasFunctionFrozenRun run =
        run(
            secondAdapter.functions().get(0),
            "draw",
            Map.of("ratio", "auto"),
            List.of(),
            "GPT_IMAGE_POLLING",
            Map.of("executionId", "bad", "uploads", List.of()));
    Execution bad =
        new Execution(
            "bad",
            ExecutionStatus.SUCCEEDED,
            "",
            "",
            List.of(
                new ExecutionResource("a.png", "image/png", 1L, null, "/api/resources/a"),
                new ExecutionResource("b.png", "image/png", 1L, null, "/api/resources/b")));
    when(secondClient.getExecution("bad", 0)).thenReturn(bad);
    assertThrows(
        OpenCliHubException.class,
        () -> secondAdapter.execute(new RecordingContext(Map.of()), run));
  }

  @Test
  void adaptersNeverEchoProviderFailureIntoUnknownResolution() {
    // 提交时从 Hub 抛出的失败可能含 URL/凭据；UNKNOWN 决议只保留固定文案，不带原始 message/cause。
    String marker = "https://user:s3cr3t@hub.test/exec?token=abcdef";
    Map<UUID, byte[]> inputs = Map.of(RESOURCE_100, bytes(1));

    OpenCliHubClient gptClient = mock(OpenCliHubClient.class);
    when(gptClient.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(new UploadedResource("/resources/u/a.png"));
    when(gptClient.execute(any(), anyLong()))
        .thenThrow(new OpenCliHubException("OpenCLI Hub request failed: " + marker));
    GptImage2CanvasFunctionAdapter gpt = gptAdapter(gptClient);
    CanvasFunctionFrozenRun gptRun =
        run(
            gpt.functions().get(0),
            "draw",
            Map.of("ratio", "auto"),
            List.of(image(10L, 0, 100L, "a.png")),
            "QUEUED",
            Map.of());
    CanvasFunctionUnknownException gptError =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () -> gpt.submit(new RecordingContext(inputs), gptRun));
    assertTrue(gptError.getMessage().contains("outcome unknown"));
    assertFalse(gptError.getMessage().contains("s3cr3t"));
    assertNull(gptError.getCause());

    OpenCliHubClient seedanceClient = mock(OpenCliHubClient.class);
    when(seedanceClient.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(new UploadedResource("/resources/u/a.png"));
    when(seedanceClient.execute(any(), anyLong()))
        .thenThrow(new OpenCliHubException("OpenCLI Hub request failed: " + marker));
    SeedanceCanvasFunctionAdapter seedance = seedanceAdapter(seedanceClient, ignored -> {});
    CanvasFunctionFrozenRun seedanceRun =
        run(
            seedance.functions().get(0),
            "draw",
            Map.of("ratio", "16:9", "duration", 5),
            List.of(image(10L, 0, 100L, "a.png")),
            "QUEUED",
            Map.of());
    CanvasFunctionUnknownException seedanceError =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () -> seedance.submit(new RecordingContext(inputs), seedanceRun));
    assertTrue(seedanceError.getMessage().contains("outcome unknown"));
    assertFalse(seedanceError.getMessage().contains("s3cr3t"));
    assertNull(seedanceError.getCause());
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
            adapter.functions().get(0),
            "use it",
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
            adapter.functions().get(0),
            "use it",
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
        adapter.functions().stream().map(CanvasFunctionDefinition::name).toList());
    assertTrue(
        adapter.functions().stream()
            .allMatch(
                def ->
                    def.outputs().size() == 1
                        && def.outputs().get(0).kind() == CanvasResourceKind.VIDEO));

    CanvasFunctionFrozenReference image = image(10L, 0, 100L, "image.png");
    CanvasFunctionFrozenReference video =
        reference(11L, 0, 101L, CanvasResourceKind.VIDEO, "video.mp4", "video/mp4", 2000L);
    CanvasFunctionFrozenReference audio =
        reference(12L, 0, 102L, CanvasResourceKind.AUDIO, "audio.mp3", "audio/mpeg", 3000L);
    CanvasFunctionFrozenRun run =
        run(
            adapter.functions().get(1),
            "Start @视频1 then @图片1 with @音频1",
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

    adapter.submit(context, run);
    Map<String, Object> submittedState =
        Map.of(
            "assetId", "0123456789abcdef",
            "pollStartedAt", Instant.now().toString(),
            "uploads", context.lastAdapterState.get("uploads"));
    CanvasFunctionFrozenRun submittedRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            binding(adapter.functions().get(1)),
            run.args(),
            run.manifest(),
            run.outputs(),
            CanvasFunctionSubmitState.SUBMITTED,
            "SEEDANCE_STATUS_WAITING",
            submittedState);

    assertEquals(List.of(TARGET), adapter.execute(context, submittedRun));

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
    CanvasFunctionDefinition def = adapter.functions().get(0);
    CanvasFunctionFrozenReference badCodec =
        reference(10L, 0, 100L, CanvasResourceKind.VIDEO, "bad.webp", "video/webp", 2000L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.preflight(
                run(
                    def,
                    "x",
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
                    def,
                    "x",
                    Map.of("ratio", "16:9", "duration", 5),
                    tooLong,
                    "QUEUED",
                    Map.of())));

    CanvasFunctionFrozenRun unknown =
        run(
            def,
            "x",
            Map.of("ratio", "16:9", "duration", 5),
            List.of(),
            "SEEDANCE_SUBMITTING",
            Map.of());
    assertThrows(
        CanvasFunctionUnknownException.class,
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
                    def,
                    "x",
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
            adapter.functions().get(0),
            "x",
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
              adapter.functions().get(0),
              "x",
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
            adapter.functions().get(0),
            "x",
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
              adapter.functions().get(0),
              "draw",
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
            adapter.functions().get(0),
            "draw",
            Map.of("ratio", "auto"),
            List.of(),
            "GPT_IMAGE_POLLING",
            Map.of("executionId", "existing", "uploads", List.of()));

    assertEquals(List.of(TARGET), adapter.execute(new RecordingContext(Map.of()), run));
    verify(client).getExecution("existing", 120);

    CanvasFunctionFrozenRun noExecution =
        run(
            adapter.functions().get(0),
            "draw",
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
            adapter.functions().get(0),
            "x",
            Map.of("ratio", "16:9", "duration", 5),
            List.of(),
            "SEEDANCE_STATUS_WAITING",
            Map.of(
                "assetId",
                "0123456789abcdef",
                "pollStartedAt",
                Instant.now().toString(),
                "uploads",
                List.of()));
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
      when(client.execute(any(), anyLong()))
          .thenReturn(new Execution("submit-exec", ExecutionStatus.PENDING, "", "", List.of()));
      when(client.getExecution("submit-exec", 0))
          .thenReturn(
              new Execution("submit-exec", ExecutionStatus.SUCCEEDED, stdout, "", List.of()));
      CanvasFunctionFrozenRun run =
          run(
              adapter.functions().get(0),
              "x",
              Map.of("ratio", "16:9", "duration", 5),
              List.of(),
              "QUEUED",
              Map.of());
      assertThrows(
          OpenCliHubException.class, () -> adapter.submit(new RecordingContext(Map.of()), run));
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
                    gpt.functions().get(0),
                    "x",
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
                    seedance.functions().get(0),
                    "x",
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
                    seedance.functions().get(0),
                    "x",
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
            gpt.functions().get(0),
            "x",
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
            gpt.functions().get(0),
            "x",
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

  /** 目录外的 Seedance 函数名必须在 preflight 失败，绝不猜测参数。 */
  @Test
  void seedancePreflightRejectsUnsupportedFunctionName() {
    SeedanceCanvasFunctionAdapter adapter =
        seedanceAdapter(mock(OpenCliHubClient.class), ignored -> {});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.preflight(
                    run(
                        renamed(adapter.functions().get(0), "unsupported-seedance"),
                        "a prompt",
                        Map.of(),
                        List.of(),
                        "INITIAL",
                        Map.of())));

    assertTrue(error.getMessage().contains("unsupported Seedance function"));
  }

  /** 空白提示词在 preflight 就被拒绝，绝不带着空白提交。 */
  @Test
  void seedancePreflightRejectsBlankPrompt() {
    SeedanceCanvasFunctionAdapter adapter =
        seedanceAdapter(mock(OpenCliHubClient.class), ignored -> {});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.preflight(
                    run(
                        adapter.functions().get(0),
                        "   ",
                        Map.of("ratio", "16:9"),
                        List.of(),
                        "INITIAL",
                        Map.of())));

    assertTrue(error.getMessage().contains("Seedance prompt must not be blank"));
  }

  /** 引用清单约束：图片 MIME、音频容器、视频时长与引用数量上限都由 preflight 逐一拒绝。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSeedanceManifests")
  void seedancePreflightRejectsInvalidReferences(
      String scenario, List<CanvasFunctionFrozenReference> manifest, String expectedMessage) {
    SeedanceCanvasFunctionAdapter adapter =
        seedanceAdapter(mock(OpenCliHubClient.class), ignored -> {});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.preflight(
                    run(
                        adapter.functions().get(0),
                        "valid prompt",
                        Map.of("ratio", "16:9"),
                        manifest,
                        "INITIAL",
                        Map.of())));

    assertTrue(
        error.getMessage().contains(expectedMessage), scenario + ": " + error.getMessage());
  }

  private static Stream<Arguments> invalidSeedanceManifests() {
    return Stream.of(
        Arguments.of(
            "image reference with a non-image MIME type",
            List.of(
                reference(
                    1L, 0, 100L, CanvasResourceKind.IMAGE, "file.pdf", "application/pdf", null)),
            "Seedance IMAGE reference must use image/* MIME"),
        Arguments.of(
            "audio reference with an unsupported container",
            List.of(
                reference(1L, 0, 101L, CanvasResourceKind.AUDIO, "audio.ogg", "audio/ogg", 5000L)),
            "Seedance AUDIO requires audio/wav or audio/mpeg"),
        Arguments.of(
            "video shorter than two seconds",
            List.of(
                reference(1L, 0, 102L, CanvasResourceKind.VIDEO, "short.mp4", "video/mp4", 1000L)),
            "VIDEO duration must be between 2 and 15 seconds"),
        Arguments.of(
            "more than three videos",
            List.of(
                reference(1L, 0, 100L, CanvasResourceKind.VIDEO, "v1.mp4", "video/mp4", 2500L),
                reference(1L, 1, 101L, CanvasResourceKind.VIDEO, "v2.mp4", "video/mp4", 2500L),
                reference(1L, 2, 102L, CanvasResourceKind.VIDEO, "v3.mp4", "video/mp4", 2500L),
                reference(1L, 3, 103L, CanvasResourceKind.VIDEO, "v4.mp4", "video/mp4", 2500L)),
            "Seedance accepts at most 3 VIDEO and 3 AUDIO references"));
  }

  /** checkpoint 里已有 assetId 的恢复必须立即返回，绝不重新提交或重新上传。 */
  @Test
  void seedanceSubmitReturnsImmediatelyWhenTheAssetIsAlreadyKnown() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});

    RecordingContext context = new RecordingContext(Map.of());
    adapter.submit(
        context,
        run(
            adapter.functions().get(0),
            "prompt",
            Map.of("ratio", "16:9"),
            List.of(),
            "INITIAL",
            Map.of(OpenCliAdapterState.ASSET_ID, "existing-asset")));

    verifyNoInteractions(client);
  }

  /** 提交结果未知时的恢复：按 executionId 轮询到 submitted 并写回 assetId，绝不重复提交。 */
  @Test
  void seedanceSubmissionPollingRecoversTheAssetIdWithoutResubmitting() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
    when(client.getExecution("exec-polling", 0))
        .thenReturn(new Execution("exec-polling", ExecutionStatus.RUNNING, "", "", List.of()));
    when(client.getExecution("exec-polling", 120))
        .thenReturn(
            new Execution(
                "exec-polling",
                ExecutionStatus.SUCCEEDED,
                "{\"submitted\":true,\"status\":\"submitted\",\"assetId\":\"0123456789abcdef\"}",
                "",
                List.of()));

    RecordingContext context = new RecordingContext(Map.of());
    adapter.submit(
        context,
        run(
            adapter.functions().get(0),
            "prompt",
            Map.of("ratio", "16:9"),
            List.of(),
            "SEEDANCE_SUBMISSION_POLLING",
            Map.of(OpenCliAdapterState.EXECUTION_ID, "exec-polling", "uploads", List.of())));

    assertEquals("0123456789abcdef", context.lastAdapterState.get(OpenCliAdapterState.ASSET_ID));
  }

  /** 运行已停止时提交轮询必须立刻失败，不再消耗远端查询。 */
  @Test
  void seedanceSubmissionPollingFailsWhenTheRunIsNoLongerRunning() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    SeedanceCanvasFunctionAdapter adapter = seedanceAdapter(client, ignored -> {});
    when(client.getExecution("exec-stopped", 0))
        .thenReturn(new Execution("exec-stopped", ExecutionStatus.RUNNING, "", "", List.of()));
    RecordingContext context = new RecordingContext(Map.of());
    context.stop();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.submit(
                    context,
                    run(
                        adapter.functions().get(0),
                        "prompt",
                        Map.of("ratio", "16:9"),
                        List.of(),
                        "SEEDANCE_SUBMISSION_POLLING",
                        Map.of(
                            OpenCliAdapterState.EXECUTION_ID,
                            "exec-stopped",
                            "uploads",
                            List.of()))));

    assertTrue(error.getMessage().contains("run is no longer running"));
  }

  /** 参数类型不符必须 fail closed 成未知结果，绝不按字符串猜测。 */
  @Test
  void seedanceSubmitFailsClosedWhenAnArgumentHasTheWrongType() {
    SeedanceCanvasFunctionAdapter adapter =
        seedanceAdapter(mock(OpenCliHubClient.class), ignored -> {});

    assertThrows(
        CanvasFunctionUnknownException.class,
        () ->
            adapter.submit(
                new RecordingContext(Map.of()),
                run(
                    adapter.functions().get(0),
                    "prompt",
                    Map.of("ratio", 16),
                    List.of(),
                    "INITIAL",
                    Map.of())));
  }

  /** 目录外的 GPT Image 函数名必须在 preflight 失败，绝不猜测参数。 */
  @Test
  void gptImagePreflightRejectsUnsupportedFunctionName() {
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(mock(OpenCliHubClient.class));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.preflight(
                    run(
                        renamed(adapter.functions().get(0), "unsupported-gpt"),
                        "a prompt",
                        Map.of(),
                        List.of(),
                        "INITIAL",
                        Map.of())));

    assertTrue(error.getMessage().contains("unsupported GPT Image function"));
  }

  /** 空白提示词在 preflight 就被拒绝，绝不带着空白提交。 */
  @Test
  void gptImagePreflightRejectsBlankPrompt() {
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(mock(OpenCliHubClient.class));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.preflight(
                    run(
                        adapter.functions().get(0),
                        "   ",
                        Map.of(),
                        List.of(),
                        "INITIAL",
                        Map.of())));

    assertTrue(error.getMessage().contains("GPT Image prompt must not be blank"));
  }

  /** 已有 executionId 的恢复必须立即返回，绝不重新提交。 */
  @Test
  void gptImageSubmitReturnsImmediatelyWhenTheExecutionIsAlreadyKnown() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);

    RecordingContext context = new RecordingContext(Map.of());
    adapter.submit(
        context,
        run(
            adapter.functions().get(0),
            "prompt",
            Map.of(),
            List.of(),
            "GPT_IMAGE_POLLING",
            Map.of(OpenCliAdapterState.EXECUTION_ID, "exec-already")));

    verifyNoInteractions(client);
  }

  /** 轮询 RUNNING 到 SUCCEEDED，并把唯一的图片产物交给画布。 */
  @Test
  void gptImageExecutePollsTheRunningExecutionAndMaterializesTheImage() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    when(client.getExecution("exec-polling", 0))
        .thenReturn(new Execution("exec-polling", ExecutionStatus.RUNNING, "", "", List.of()));
    ExecutionResource imageResource =
        new ExecutionResource("res.png", "image/png", 100L, null, "/api/resources/res.png");
    when(client.getExecution("exec-polling", 120))
        .thenReturn(
            new Execution(
                "exec-polling", ExecutionStatus.SUCCEEDED, "", "", List.of(imageResource)));
    when(client.openResource(imageResource))
        .thenReturn(
            new HubResourceStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L, "image/png"));

    RecordingContext context = new RecordingContext(Map.of());
    List<UUID> result =
        adapter.execute(
            context,
            run(
                adapter.functions().get(0),
                "prompt",
                Map.of(),
                List.of(),
                "GPT_IMAGE_POLLING",
                Map.of(OpenCliAdapterState.EXECUTION_ID, "exec-polling", "uploads", List.of())));

    assertEquals(1, result.size());
  }

  /** 运行已停止时轮询必须失败，不再等待下一次查询。 */
  @Test
  void gptImageExecuteFailsWhenTheRunIsNoLongerRunning() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(client);
    when(client.getExecution("exec-stopped", 0))
        .thenReturn(new Execution("exec-stopped", ExecutionStatus.RUNNING, "", "", List.of()));
    RecordingContext context = new RecordingContext(Map.of());
    context.stop();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.execute(
                    context,
                    run(
                        adapter.functions().get(0),
                        "prompt",
                        Map.of(),
                        List.of(),
                        "GPT_IMAGE_POLLING",
                        Map.of(
                            OpenCliAdapterState.EXECUTION_ID, "exec-stopped", "uploads", List.of()))));

    assertTrue(error.getMessage().contains("run is no longer running"));
  }

  /** 参数类型不符必须 fail closed 成未知结果，绝不按字符串猜测。 */
  @Test
  void gptImageSubmitFailsClosedWhenAnArgumentHasTheWrongType() {
    GptImage2CanvasFunctionAdapter adapter = gptAdapter(mock(OpenCliHubClient.class));

    assertThrows(
        CanvasFunctionUnknownException.class,
        () ->
            adapter.submit(
                new RecordingContext(Map.of()),
                run(
                    adapter.functions().get(0),
                    "prompt",
                    Map.of("ratio", 16),
                    List.of(),
                    "INITIAL",
                    Map.of())));
  }

  /** 同一份定义换名：断言适配器只接受目录内的函数名。 */
  private static CanvasFunctionDefinition renamed(
      CanvasFunctionDefinition definition, String name) {
    return new CanvasFunctionDefinition(
        name,
        definition.description(),
        definition.argsSchema(),
        definition.outputs(),
        definition.referencePolicy());
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
        adapter.functions().get(0),
        "x",
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

  /** 已启用的 GPT Image：只显式给出本用例需要的业务开关，其余取真实 schema 默认值。 */
  private static GptImage2CanvasFunctionAdapter gptAdapter(OpenCliHubClient client) {
    return new GptImage2CanvasFunctionAdapter(
        OpenCliTestSettings.parse(
            "{\"enabled\":true,\"baseUrl\":\"http://hub.local\","
                + "\"gptImage2\":{\"paidEnabled\":true}}"),
        client);
  }

  /** 已启用的 Seedance：轮询间隔 5ms 让等待预算用例可断言，其余取真实 schema 默认值。 */
  private static SeedanceCanvasFunctionAdapter seedanceAdapter(
      OpenCliHubClient client, SeedanceCanvasFunctionAdapter.Sleeper sleeper) {
    return new SeedanceCanvasFunctionAdapter(
        OpenCliTestSettings.parse(
            "{\"enabled\":true,\"baseUrl\":\"http://hub.local\","
                + "\"seedance\":{\"enabled\":true,\"workspaceId\":\"workspace-1\","
                + "\"retry\":2,\"statusPollIntervalMillis\":5}}"),
        client,
        MAPPER,
        sleeper);
  }

  private static CanvasFunctionFrozenRun run(
      CanvasFunctionDefinition definition,
      String prompt,
      Map<String, Object> parameters,
      List<CanvasFunctionFrozenReference> manifest,
      String stage,
      Map<String, Object> state) {
    Map<String, CanvasJson> argsMap = new LinkedHashMap<>();
    if (prompt != null) {
      argsMap.put("prompt", new JsonText(prompt));
    }
    if (parameters != null) {
      for (Map.Entry<String, Object> entry : parameters.entrySet()) {
        if (entry.getValue() instanceof String s) {
          argsMap.put(entry.getKey(), new JsonText(s));
        } else if (entry.getValue() instanceof Number n) {
          argsMap.put(entry.getKey(), new JsonNumber(new BigDecimal(n.toString())));
        } else if (entry.getValue() instanceof Boolean b) {
          argsMap.put(entry.getKey(), new JsonBool(b));
        }
      }
    }
    CanvasFunctionOutputSpec spec = definition.outputs().get(0);
    String outputName = spec.resolveName("output");
    List<CanvasFunctionFrozenOutput> outputs =
        List.of(new CanvasFunctionFrozenOutput(TARGET, 0, spec.kind(), outputName));
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "output",
        REQUEST,
        binding(definition),
        new JsonObject(argsMap),
        manifest,
        outputs,
        CanvasFunctionSubmitState.SUBMITTED,
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
        100L,
        kind == CanvasResourceKind.IMAGE ? 512L : 512L,
        kind == CanvasResourceKind.IMAGE ? 512L : 512L,
        durationMs);
  }

  private static byte[] bytes(int length) {
    return new byte[length];
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final Map<UUID, byte[]> resources;
    private final List<String> stages = new ArrayList<>();
    private final List<UUID> openedResourceIds = new ArrayList<>();
    private Map<String, Object> lastAdapterState = Map.of();
    private byte[] materialized;
    private boolean running = true;

    private RecordingContext(Map<UUID, byte[]> resources) {
      this.resources = resources;
    }

    void stop() {
      running = false;
    }

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      stages.add(stage);
      this.lastAdapterState = adapterState;
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      openedResourceIds.add(reference.resourceId());
      byte[] data = resources.get(reference.resourceId());
      if (data == null) {
        throw new IllegalArgumentException("unknown fixture resource: " + reference.resourceId());
      }
      return new CanvasFunctionResourceStream(
          new ByteArrayInputStream(data), (long) data.length, () -> {});
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
      try {
        materialized = content.readAllBytes();
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
      return output.resourceId();
    }

    @Override
    public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
      throw new UnsupportedOperationException();
    }
  }

  /** 内置函数绑定：目录 epoch 固定为 1，无插件来源。 */
  private static CanvasFunctionBinding binding(CanvasFunctionDefinition definition) {
    return new CanvasFunctionBinding(1L, definition, null);
  }
}
