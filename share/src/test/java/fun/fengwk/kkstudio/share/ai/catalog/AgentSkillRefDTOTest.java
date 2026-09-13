package fun.fengwk.kkstudio.share.ai.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证 {@link AgentSkillRefDTO} 的 JavaBean 契约与严格未知字段拒绝。 */
class AgentSkillRefDTOTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 测试意图：验证 AgentSkillRefDTO 能够正确序列化和反序列化 canonical 来源 ID 与 Skill 短名。 */
  @Test
  void roundTripsCanonicalFields() throws Exception {
    AgentSkillRefDTO dto = new AgentSkillRefDTO("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "dev");
    String json = objectMapper.writeValueAsString(dto);
    AgentSkillRefDTO decoded = objectMapper.readValue(json, AgentSkillRefDTO.class);
    assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", decoded.getSourceId());
    assertEquals("dev", decoded.getName());
    assertEquals(dto, decoded);
  }

  /** 测试意图：验证 AgentSkillRefDTO 严格拒绝未知的多余字段，防止客户端提交未定义属性。 */
  @Test
  void rejectsUnknownFields() {
    String json =
        "{\"sourceId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"name\":\"dev\",\"extra\":\"value\"}";
    assertThrows(Exception.class, () -> objectMapper.readValue(json, AgentSkillRefDTO.class));
  }
}
