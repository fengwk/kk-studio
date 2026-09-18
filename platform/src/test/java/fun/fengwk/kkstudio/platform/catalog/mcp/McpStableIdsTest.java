package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;

import java.util.Optional;
import java.util.UUID;

/**
 * MCP 工具稳定全局身份测试。
 *
 * <p>验证 ContributionId = {@code platform.mcp} + {@code tool.<32hex>}；模型可见身份是 mcp_tool.model_name，
 * 不在此派生。
 */
class McpStableIdsTest {

  @Test
  void derivesStableLocalName() {
    // 意图：验证稳定 UUID 派生为 ContributionId localName
    UUID toolId = UUID.fromString("12345678-1234-5678-1234-567812345678");
    assertEquals("tool.12345678123456781234567812345678", McpStableIds.localName(toolId));
    assertEquals("tool.", McpStableIds.LOCAL_NAME_PREFIX);
    assertEquals(new ContributorId("platform.mcp"), McpStableIds.CONTRIBUTOR_ID);
  }

  @Test
  void canonicalUuidFormattingAndParsing() {
    // 意图：验证 32 位无连字符 hex UUID 的序列化与解析，并严格拒绝大写与非 hex
    UUID id = UUID.randomUUID();
    String canonical = McpStableIds.canonicalUuid(id);
    assertEquals(32, canonical.length());
    assertFalse(canonical.contains("-"));

    Optional<UUID> parsed = McpStableIds.parseCanonicalUuid(canonical);
    assertTrue(parsed.isPresent());
    assertEquals(id, parsed.get());

    assertFalse(McpStableIds.parseCanonicalUuid(null).isPresent());
    assertFalse(McpStableIds.parseCanonicalUuid("too-short").isPresent());
    assertFalse(McpStableIds.parseCanonicalUuid("xyz-not-a-valid-hex-uuid-string!").isPresent());
    // 必须拒绝大写 hex 字符
    assertFalse(McpStableIds.parseCanonicalUuid("ABCDEF0123456789ABCDEF0123456789").isPresent());
    assertFalse(McpStableIds.parseCanonicalUuid("abcdef0123456789abcdef012345678A").isPresent());
  }

  @Test
  void nullChecks() {
    // 意图：验证公共方法的非空检查
    assertThrows(NullPointerException.class, () -> McpStableIds.localName(null));
    assertThrows(NullPointerException.class, () -> McpStableIds.canonicalUuid(null));
  }
}
