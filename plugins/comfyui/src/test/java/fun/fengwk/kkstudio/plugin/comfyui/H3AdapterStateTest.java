package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * adapterState 测试证明 checkpoint 完整 round-trip，并对未知、错型和非精确嵌套结构 fail closed。
 *
 * <p>{@link H3AdapterState#decode(Map)} 使用自有纯 mapper，不接收宿主 mapper，因此不受 Host HTTP 的 Long→String
 * profile 影响。
 */
class H3AdapterStateTest {

  private static final UUID UPLOAD_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID UPLOAD_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final long LONG_SEED_BEYOND_DOUBLE = 9_007_199_254_740_993L;

  @Test
  void roundTripsCompleteStateAndImmutableUpdates() {
    H3AdapterState state =
        H3AdapterState.empty()
            .withSeed(0L)
            .withHarnessThreadId(new UUID(0L, 2L))
            .withEnhancedPrompt("增强提示")
            .withUpload(UPLOAD_1, new H3UploadedFile("11.png", "kk-studio/7", "input"))
            .withPromptId("prompt-1")
            .withOutputs(List.of(new H3OutputDescriptor("result.mp4", "", "output")));

    assertEquals(state, H3AdapterState.decode(state.encode()));
    assertEquals(1, state.uploads().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> state.uploads().put(UPLOAD_2, new H3UploadedFile("12.png", "x", "input")));
  }

  /**
   * 宿主 HTTP mapper 会把 Long 序列化为字符串（Web 的 Long→String serializer）；state 解码必须用自有纯 mapper， 不继承该
   * profile，否则合法 Long seed 会被读成 StringNode 而被拒绝。
   */
  @Test
  void decodesLongSeedIndependentlyOfHostLongToStringProfile() {
    SimpleModule longToString = new SimpleModule("LongToStringModule");
    longToString.addSerializer(Long.class, new ToStringSerializer(Long.class));
    longToString.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    ObjectMapper hostMapper = JsonMapper.builder().addModule(longToString).build();

    // 复现渗漏面：同一 rawMap 经宿主 mapper 的 valueToTree 会把 Long 变成字符串节点。
    assertTrue(hostMapper.valueToTree(Map.of("seed", 5L)).get("seed").isTextual());

    // 新边界不接收外部 mapper：合法 Long seed 原样还原，超过 double 精度的值也不失真。
    assertEquals(
        LONG_SEED_BEYOND_DOUBLE,
        H3AdapterState.decode(Map.of("seed", LONG_SEED_BEYOND_DOUBLE)).seed().longValue());
  }

  /** 多次 checkpoint 往返必须无损恢复合法 Long seed（含超过 double 精度的 2^53+1）。 */
  @Test
  void restoresLongSeedExactlyAcrossRepeatedCheckpoints() {
    H3AdapterState state = H3AdapterState.empty().withSeed(LONG_SEED_BEYOND_DOUBLE);
    for (int index = 0; index < 3; index++) {
      state = H3AdapterState.decode(state.encode());
      assertEquals(LONG_SEED_BEYOND_DOUBLE, state.seed().longValue());
    }
  }

  /** 字符串或非整型 seed 继续严格拒绝；新 state 边界不接受任何外部 mapper 放宽该规则。 */
  @Test
  void stillRejectsNonIntegralSeed() {
    assertThrows(IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("seed", "1")));
    assertThrows(IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("seed", 1.5)));
  }

  @Test
  void rejectsInvalidScalarState() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new H3AdapterState(-1L, null, null, Map.of(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("harnessThreadId", "not-a-uuid")));
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
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("future", true)));
    assertThrows(IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("seed", -1)));
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("harnessThreadId", 0)));
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("promptId", " ")));
  }

  @Test
  void rejectsInvalidUploadAndOutputShapes() {
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("uploads", "bad")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "uploads",
                    Map.of("0", Map.of("name", "a", "subfolder", "b", "type", "input")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "uploads",
                    Map.of(
                        "999999999999999999999999",
                        Map.of("name", "a", "subfolder", "b", "type", "input")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> H3AdapterState.decode(Map.of("uploads", Map.of("1", "bad"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "uploads",
                    Map.of(
                        "1",
                        Map.of("name", "a", "subfolder", "b", "type", "input", "extra", true)))));
    assertThrows(
        IllegalArgumentException.class, () -> H3AdapterState.decode(Map.of("outputs", "bad")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of("outputs", List.of(Map.of("filename", "a", "subfolder", "")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "outputs",
                    List.of(Map.of("filename", "a", "subfolder", 1, "type", "output")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            H3AdapterState.decode(
                Map.of(
                    "outputs",
                    List.of(Map.of("filename", " ", "subfolder", "", "type", "output")))));
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
