package fun.fengwk.kkstudio.platform.catalog.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;

/** Tool catalog 的 wire 契约：直接复用领域三态枚举，没有固定环境身份字段；provider 是嵌套对象。 */
class ToolCatalogDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void toolCatalogEntryExposesDomainEnvironmentSupport() throws Exception {
    // 意图：Tool catalog 与 Thread Debug 共用领域三态语义，客户端只需实现一次。
    assertEquals(
        EnvironmentSupport.class,
        ToolCatalogEntryDTO.class.getDeclaredField("environmentSupport").getType());

    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setName("read");
    dto.setDescription("Read a file.");
    dto.setEnvironmentSupport(EnvironmentSupport.ROUTED);
    dto.setProvider(new ToolProviderDTO(ToolProviderKind.BUILTIN, "builtin", "Built-in"));

    String json = MAPPER.writeValueAsString(dto);
    assertTrue(json.contains("\"environmentSupport\":\"ROUTED\""), json);
    assertTrue(
        json.contains(
            "\"provider\":{\"kind\":\"BUILTIN\",\"id\":\"builtin\",\"name\":\"Built-in\"}"),
        json);
    assertEquals(3, EnvironmentSupport.values().length);
    assertEquals(4, ToolProviderKind.values().length);
  }
}
