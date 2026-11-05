package fun.fengwk.kkstudio.platform.catalog.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;

import java.util.Collections;
import java.util.List;

class AgentModelGroupConfigCodecTest {

  private final AgentModelGroupConfigCodec codec =
      new AgentModelGroupConfigCodec(JsonMapper.builder().build());

  /** 已存配置和 DTO 往返保留成员顺序、回退状态码及冷却，存储形状不依赖 DTO。 */
  @Test
  void roundTripsStoredGroup() {
    String json =
        """
        {"members":[{"modelName":"first","variant":"fast"},{"modelName":"second","variant":"safe"}],
         "fallbackStatusCodes":[429,503],"cooldownSeconds":60}
        """;
    AgentModelGroupDTO dto = codec.decode(json);
    assertEquals(
        List.of("first", "second"), dto.getMembers().stream().map(m -> m.getModelName()).toList());
    assertEquals(List.of(429, 503), dto.getFallbackStatusCodes());
    assertEquals(60, dto.getCooldownSeconds());
    assertEquals(dto, codec.decode(codec.encode(dto)));
    assertEquals(
        JsonMapper.builder().build().readTree(json),
        JsonMapper.builder().build().readTree(codec.encode(dto)));
  }

  /** 损坏或不符合业务约束的存储 JSON 仍被拒绝，不因转换层而绕过严格校验。 */
  @Test
  void rejectsInvalidStoredGroups() {
    for (String json :
        List.of(
            "",
            "null",
            "{\"members\":[],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}",
            "{\"members\":[null],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"},{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[399],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":-1}",
            "{\"members\":[{\"modelName\":\" a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0,\"extra\":1}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\",\"extra\":1}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[429,429],\"cooldownSeconds\":0}",
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":1.5}",
            "{\"members\":[],\"members\":[],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}")) {
      assertThrows(IllegalArgumentException.class, () -> codec.decode(json), json);
    }
  }

  /** 写入 DTO 同样拒绝无效引用和超长成员列表，而不是序列化成不可读取的记录。 */
  @Test
  void rejectsInvalidDtosBeforeEncoding() {
    AgentModelGroupDTO group =
        codec.decode(
            "{\"members\":[{\"modelName\":\"a\",\"variant\":\"v\"}],\"fallbackStatusCodes\":[],\"cooldownSeconds\":0}");
    var member = group.getMembers().getFirst();
    member.setModelName("x".repeat(129));
    assertEquals(
        "group.members[0].modelName must be <= 128 characters",
        assertThrows(IllegalArgumentException.class, () -> codec.encode(group)).getMessage());
    member.setModelName("a");
    group.setMembers(Collections.nCopies(65, member));
    assertEquals(
        "group.members must not exceed 64",
        assertThrows(IllegalArgumentException.class, () -> codec.encode(group)).getMessage());
  }
}
