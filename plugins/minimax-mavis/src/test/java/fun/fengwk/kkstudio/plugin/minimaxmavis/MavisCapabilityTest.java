package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 15 项能力与模型工具、MCP endpoint 的稳定映射。
 *
 * <p>工具名是 Agent 配置与权限的稳定产品身份，因此这里逐项锁定文档约定的 15 个名字、endpoint 与请求超时。
 */
class MavisCapabilityTest {

  private static final List<String> EXPECTED_TOOL_NAMES =
      List.of(
          "mavis_web_search",
          "mavis_extract_web",
          "mavis_image_search",
          "mavis_reverse_image",
          "mavis_understand_image",
          "mavis_understand_audio",
          "mavis_understand_video",
          "mavis_asr",
          "mavis_list_voices",
          "mavis_tts",
          "mavis_tts_batch",
          "mavis_generate_image",
          "mavis_generate_music",
          "mavis_submit_video",
          "mavis_query_video");

  private static final List<String> EXPECTED_ENDPOINTS =
      List.of(
          "web_search",
          "extract_content_from_websites",
          "images_search_and_download",
          "image_reverse_search",
          "images_understand",
          "audios_understand",
          "videos_understand",
          "listen_audio",
          "get_voice_list",
          "synthesize_speech",
          "batch_text_to_audio",
          "image_synthesize",
          "batch_text_to_music",
          "submit_video_generation",
          "query_video_generation");

  /** 枚举顺序、工具名与 endpoint 与文档约定的 15 项模型能力一一对应。 */
  @Test
  void mapsFifteenCapabilitiesInDocumentedOrder() {
    List<String> toolNames =
        Arrays.stream(MavisCapability.values()).map(MavisCapability::toolName).toList();
    List<String> endpoints =
        Arrays.stream(MavisCapability.values()).map(MavisCapability::endpoint).toList();

    assertEquals(15, MavisCapability.values().length);
    assertEquals(EXPECTED_TOOL_NAMES, toolNames);
    assertEquals(EXPECTED_ENDPOINTS, endpoints);
  }

  /** 能力 id、工具名与 endpoint 都唯一，可安全作为资源名与目录键。 */
  @Test
  void capabilityIdentitiesAreUnique() {
    Set<String> ids = new HashSet<>();
    Set<String> toolNames = new HashSet<>();
    Set<String> endpoints = new HashSet<>();
    for (MavisCapability capability : MavisCapability.values()) {
      assertTrue(ids.add(capability.id()), "duplicate capability id: " + capability.id());
      assertTrue(toolNames.add(capability.toolName()));
      assertTrue(endpoints.add(capability.endpoint()));
      assertTrue(capability.id().matches("[a-z]+(-[a-z]+)*"));
    }
    assertEquals(15, ids.size());
  }

  /** 请求超时按网关观测能力分层：生成与语音 600 秒、多模态理解 300 秒、其余 120 秒。 */
  @Test
  void requestTimeoutsFollowObservedEndpointClasses() {
    assertEquals(Duration.ofSeconds(600), MavisCapability.TTS.requestTimeout());
    assertEquals(Duration.ofSeconds(600), MavisCapability.TTS_BATCH.requestTimeout());
    assertEquals(Duration.ofSeconds(600), MavisCapability.GENERATE_IMAGE.requestTimeout());
    assertEquals(Duration.ofSeconds(600), MavisCapability.GENERATE_MUSIC.requestTimeout());
    assertEquals(Duration.ofSeconds(600), MavisCapability.SUBMIT_VIDEO.requestTimeout());
    assertEquals(Duration.ofSeconds(300), MavisCapability.UNDERSTAND_VIDEO.requestTimeout());
    assertEquals(Duration.ofSeconds(120), MavisCapability.WEB_SEARCH.requestTimeout());
    assertEquals(Duration.ofSeconds(120), MavisCapability.QUERY_VIDEO.requestTimeout());
  }

  /** 工具名可以反查回能力，未知名字返回空。 */
  @Test
  void resolvesCapabilityByToolName() {
    assertEquals(
        MavisCapability.UNDERSTAND_IMAGE,
        MavisCapability.fromToolName("mavis_understand_image").orElseThrow());
    assertEquals(
        MavisCapability.WEB_SEARCH,
        MavisCapability.fromToolName(" MAVIS_WEB_SEARCH ").orElseThrow());
    assertFalse(MavisCapability.fromToolName("mavis_unknown").isPresent());
    assertFalse(MavisCapability.fromToolName(null).isPresent());
  }
}
