package fun.fengwk.kkstudio.core.studio.function.opencli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliAdapterState.UploadedInput;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.UploadedResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Checkpoint、上传恢复和配置边界必须在调用 provider 前 fail closed。 */
class OpenCliAdapterSupportTest {

  private static final UUID RESOURCE_7 = new UUID(0L, 7L);
  private static final UUID RESOURCE_100 = new UUID(0L, 100L);
  private static final UUID RESOURCE_101 = new UUID(0L, 101L);
  private static final UUID RESOURCE_999 = new UUID(0L, 999L);

  @Test
  void adapterStateRoundTripsUploadsAndRejectsMalformedCheckpointValues() {
    Map<String, Object> state = new LinkedHashMap<>();
    assertNull(OpenCliAdapterState.optionalString(state, "missing"));
    state.put("text", "value");
    assertEquals("value", OpenCliAdapterState.optionalString(state, "text"));
    state.put("text", " ");
    assertThrows(
        IllegalArgumentException.class, () -> OpenCliAdapterState.optionalString(state, "text"));
    state.put("text", 1);
    assertThrows(
        IllegalArgumentException.class, () -> OpenCliAdapterState.optionalString(state, "text"));

    List<UploadedInput> expected = List.of(new UploadedInput(RESOURCE_7, "/resources/date/a.png"));
    OpenCliAdapterState.putUploads(state, expected);
    assertEquals(expected, OpenCliAdapterState.uploads(state));
    assertEquals(
        Map.of("resourceId", RESOURCE_7.toString(), "resourcePath", "/resources/date/a.png"),
        ((List<?>) state.get("uploads")).get(0));

    for (Object malformed :
        List.of(
            "not-an-array",
            List.of("not-an-object"),
            List.of(Map.of("resourceId", "0", "resourcePath", "/resources/a.png")),
            List.of(Map.of("resourceId", "7", "resourcePath", "/outside/a.png")),
            List.of(Map.of("resourceId", "7", "resourcePath", "/resources/%zz")),
            List.of(
                Map.of(
                    "resourceId",
                    "999999999999999999999999",
                    "resourcePath",
                    "/resources/a.png")))) {
      state.put("uploads", malformed);
      assertThrows(IllegalArgumentException.class, () -> OpenCliAdapterState.uploads(state));
    }
  }

  @Test
  void uploaderResumesOnlyMissingManifestEntriesAndCheckpointsConsistentState() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    OpenCliReferenceUploader uploader = new OpenCliReferenceUploader(client);
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    CanvasFunctionFrozenReference first = image(100L, "first.png");
    CanvasFunctionFrozenReference second = image(101L, "second.png");
    CanvasFunctionFrozenRun run = mock(CanvasFunctionFrozenRun.class);
    when(run.manifest()).thenReturn(List.of(first, second));
    when(context.isRunning()).thenReturn(true);
    when(context.openOriginal(second))
        .thenReturn(
            new CanvasFunctionResourceStream(
                new ByteArrayInputStream(new byte[] {1, 2}), 2L, () -> {}));
    when(client.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(new UploadedResource("/resources/date/second.png"));
    Map<String, Object> state = new LinkedHashMap<>();
    OpenCliAdapterState.putUploads(
        state, List.of(new UploadedInput(RESOURCE_100, "/resources/date/first.png")));

    OpenCliReferenceUploader.UploadResult result = uploader.uploadAll(context, run, state);

    assertEquals(
        List.of(
            new UploadedInput(RESOURCE_100, "/resources/date/first.png"),
            new UploadedInput(RESOURCE_101, "/resources/date/second.png")),
        result.uploads());
    verify(context).openOriginal(second);
    verify(context, never()).openOriginal(first);
    ArgumentCaptor<String> stages = ArgumentCaptor.forClass(String.class);
    verify(context, times(2)).checkpoint(stages.capture(), any());
    assertEquals(List.of("INPUT_UPLOADING", "INPUTS_UPLOADED"), stages.getAllValues());
  }

  @Test
  void uploaderRejectsInconsistentOrStoppedRecoveryAndWrapsCloseFailure() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    OpenCliReferenceUploader uploader = new OpenCliReferenceUploader(client);
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    CanvasFunctionFrozenReference reference = image(100L, "a.png");
    CanvasFunctionFrozenRun run = mock(CanvasFunctionFrozenRun.class);
    when(run.manifest()).thenReturn(List.of(reference));

    Map<String, Object> tooMany = new LinkedHashMap<>();
    OpenCliAdapterState.putUploads(
        tooMany,
        List.of(
            new UploadedInput(RESOURCE_100, "/resources/a.png"),
            new UploadedInput(RESOURCE_101, "/resources/b.png")));
    assertThrows(IllegalArgumentException.class, () -> uploader.uploadAll(context, run, tooMany));

    Map<String, Object> wrongOrder = new LinkedHashMap<>();
    OpenCliAdapterState.putUploads(
        wrongOrder, List.of(new UploadedInput(RESOURCE_999, "/resources/a.png")));
    assertThrows(
        IllegalArgumentException.class, () -> uploader.uploadAll(context, run, wrongOrder));

    when(context.isRunning()).thenReturn(false);
    assertThrows(
        IllegalStateException.class, () -> uploader.uploadAll(context, run, new LinkedHashMap<>()));
    verify(client, never()).upload(anyString(), anyString(), anyLong(), any(InputStream.class));

    CanvasFunctionExecutionContext closeFailureContext = mock(CanvasFunctionExecutionContext.class);
    when(closeFailureContext.isRunning()).thenReturn(true);
    when(closeFailureContext.openOriginal(reference))
        .thenReturn(
            new CanvasFunctionResourceStream(
                new ByteArrayInputStream(new byte[] {1}),
                1L,
                () -> {
                  throw new IOException("close failed");
                }));
    when(client.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(new UploadedResource("/resources/a.png"));
    assertThrows(
        UncheckedIOException.class,
        () -> uploader.uploadAll(closeFailureContext, run, new LinkedHashMap<>()));
  }

  @Test
  void hubPropertiesOnlyValidateInstanceIdentity() {
    assertInvalidHub(properties -> properties.setInstanceId(" "));
    assertInvalidHub(properties -> properties.setInstanceId("x".repeat(37)));

    OpenCliHubProperties valid = new OpenCliHubProperties();
    valid.setInstanceId(" instance ");
    valid.validate();
    assertEquals("instance", valid.getInstanceId());

    OpenCliHubProperties absent = new OpenCliHubProperties();
    absent.validate();
    assertNull(absent.getInstanceId());
  }

  private static CanvasFunctionFrozenReference image(long resourceId, String name) {
    UUID resource = new UUID(0L, resourceId);
    return new CanvasFunctionFrozenReference(
        new UUID(0L, 1L),
        0,
        resource,
        resource,
        CanvasResourceKind.IMAGE,
        name,
        "image/png",
        2L,
        null,
        null,
        null);
  }

  private static void assertInvalidHub(Consumer<OpenCliHubProperties> mutation) {
    OpenCliHubProperties properties = new OpenCliHubProperties();
    mutation.accept(properties);
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
