package fun.fengwk.kkstudio.harness.daemon.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;

import java.util.Objects;

/** {@code skill.load} 成功结果的唯一 JSON 形状与可传输性校验。 */
final class SkillLoadPayload {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private SkillLoadPayload() {}

  /**
   * 编码 {@code {"body","baseDirectory"}}，并按 {@link JsonResultContent} 的 wire 上限验证。
   *
   * <p>扫描发布与实际加载共用此入口，确保 READY 中出现的每个 Skill 都能通过同一结果协议返回。
   */
  static String encode(String body, String baseDirectory) {
    ObjectNode payload = OBJECT_MAPPER.createObjectNode();
    payload.put("body", Objects.requireNonNull(body, "body"));
    payload.put("baseDirectory", Objects.requireNonNull(baseDirectory, "baseDirectory"));
    return new JsonResultContent(payload.toString()).json();
  }
}
