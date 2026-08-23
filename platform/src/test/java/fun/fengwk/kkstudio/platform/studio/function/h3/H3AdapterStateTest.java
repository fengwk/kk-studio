package fun.fengwk.kkstudio.platform.studio.function.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;

/** adapterState 测试证明 checkpoint 完整 round-trip，并对未知、错型和非精确嵌套结构 fail closed。 */
class H3AdapterStateTest {

  private static final UUID UPLOAD_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID UPLOAD_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void roundTripsCompleteStateAndImmutableUpdates() {
    H3AdapterState state =
        H3AdapterState.empty()
            .withSeed(0L)
            .withHarnessThreadId(new UUID(0L, 2L))
            .withEnhancedPrompt("增强提示")
            .withUpload(UPLOAD_1, new H3UploadedFile("11.png", "kk-studio/7", "input"))
            .withPromptId("prompt-1")
            .withOutput(new H3OutputDescriptor("result.mp4", "", "output"));

    assertEquals(state, H3AdapterState.decode(state.encode(), mapper));
    assertEquals(1, state.uploads().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> state.uploads().put(UPLOAD_2, new H3UploadedFile("12.png", "x", "input")));
  }

  @Test
  void rejectsInvalidScalarState() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new H3AdapterState(-1L, null, null, Map.of(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("harnessThreadId", "not-a-uuid"), mapper));
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.empty().withEnhancedPrompt(" "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.empty()
                .withEnhancedPrompt("界".repeat(H3AdapterState.MAX_ENHANCED_PROMPT_BYTES)));
    assertThrows(
        NullPointerException.class,
        () -> H3AdapterState.empty().withUpload(null, new H3UploadedFile("a", "b", "input")));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("future", true), mapper));
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("seed", "1"), mapper));
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("seed", -1), mapper));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("harnessThreadId", 0), mapper));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("promptId", " "), mapper));
  }

  @Test
  void rejectsInvalidUploadAndOutputShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("uploads", "bad"), mapper));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "uploads", Map.of("0", Map.of("name", "a", "subfolder", "b", "type", "input"))),
                mapper));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "uploads",
                    Map.of(
                        "999999999999999999999999",
                        Map.of("name", "a", "subfolder", "b", "type", "input"))),
                mapper));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("uploads", Map.of("1", "bad")), mapper));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "uploads",
                    Map.of(
                        "1",
                        Map.of("name", "a", "subfolder", "b", "type", "input", "extra", true))),
                mapper));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("output", "bad"), mapper));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of("output", Map.of("filename", "a", "subfolder", "")), mapper));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of("output", Map.of("filename", "a", "subfolder", 1, "type", "output")),
                mapper));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of("output", Map.of("filename", " ", "subfolder", "", "type", "output")),
                mapper));
  }

  @Test
  void validatesSmallValueObjects() {
    assertThrows(IllegalArgumentException.class, () -> new H3UploadedFile("a", "b", "output"));
    assertThrows(IllegalArgumentException.class, () -> new H3UploadedFile("a", " ", "input"));
    assertThrows(IllegalArgumentException.class, () -> new H3OutputDescriptor(" ", "", "output"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new H3ComfyDownload(new ByteArrayInputStream(new byte[0]), 0, "video/mp4"));
    assertEquals(
        "application/octet-stream",
        new H3ComfyDownload(new ByteArrayInputStream(new byte[0]), 1, " ").mediaType());
  }
}
