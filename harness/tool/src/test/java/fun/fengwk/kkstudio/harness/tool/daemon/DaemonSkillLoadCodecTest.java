package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** LOAD_SKILL 请求/响应 payload 的编解码与拒绝契约。 */
class DaemonSkillLoadCodecTest {

  private final DaemonSkillLoadCodec codec = new DaemonSkillLoadCodec();

  @Test
  void roundTripsLoadSkillRequest() {
    DaemonSkillLoadCodec.LoadSkillRequest request =
        new DaemonSkillLoadCodec.LoadSkillRequest("dev");
    DaemonSkillLoadCodec.LoadSkillRequest decoded =
        codec.decodeRequest(codec.encodeRequest(request));
    assertEquals(request, decoded);
  }

  @Test
  void roundTripsSkillLoadedIncludingFullBody() {
    String body = "---\nname: dev\ndescription: rules\n---\n# body\n";
    DaemonSkillLoadCodec.SkillLoaded loaded = new DaemonSkillLoadCodec.SkillLoaded("dev", body);
    DaemonSkillLoadCodec.SkillLoaded decoded = codec.decodeLoaded(codec.encodeLoaded(loaded));
    assertEquals(loaded, decoded);
  }

  @Test
  void roundTripsSkillLoadFailed() {
    DaemonSkillLoadCodec.SkillLoadFailed failed =
        new DaemonSkillLoadCodec.SkillLoadFailed("missing", "unknown skill: missing");
    DaemonSkillLoadCodec.SkillLoadFailed decoded = codec.decodeFailed(codec.encodeFailed(failed));
    assertEquals(failed, decoded);
  }

  @Test
  void rejectsBlankNameAndUnknownFields() {
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonSkillLoadCodec.LoadSkillRequest(" "));
    DaemonProtocolException unknown =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeRequest("{\"name\":\"dev\",\"x\":1}"));
    assertTrue(unknown.getMessage().contains("unknown field"));
    DaemonProtocolException blank =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"name\":\"\"}"));
    assertTrue(blank.getMessage().contains("name"));
  }
}
