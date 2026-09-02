package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.Optional;
import java.util.UUID;

/**
 * MCP 工具稳定全局身份测试。
 *
 * <p>验证 AgentToolId = {@code mcp.<32hex>}，ContributionId = {@code platform.mcp} + {@code
 * tool.<32hex>}，以及双向转换的准确性。
 */
class McpStableIdsTest {

  @Test
  void derivesStableAgentToolIdAndLocalName() {
    // 意图：验证稳定 UUID 派生为符合规范的 AgentToolId 与 ContributionId localName
    UUID toolId = UUID.fromString("12345678-1234-5678-1234-567812345678");
    AgentToolId agentToolId = McpStableIds.agentToolId(toolId);
    assertEquals("mcp.12345678123456781234567812345678", agentToolId.value());

    String localName = McpStableIds.localName(toolId);
    assertEquals("tool.12345678123456781234567812345678", localName);

    assertEquals(new ContributorId("platform.mcp"), McpStableIds.CONTRIBUTOR_ID);
  }

  @Test
  void parsesValidAgentToolId() {
    // 意图：验证从合法的 canonical AgentToolId 文本反解回原始 UUID
    UUID expected = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789");
    String canonical = "mcp.abcdef0123456789abcdef0123456789";

    Optional<UUID> parsed = McpStableIds.parseAgentToolId(canonical);
    assertTrue(parsed.isPresent());
    assertEquals(expected, parsed.get());
  }

  @Test
  void rejectsInvalidAgentToolIds() {
    // 意图：验证非法前缀、长度错误或非 hex 字符均返回 Optional.empty()
    assertFalse(McpStableIds.parseAgentToolId(null).isPresent());
    assertFalse(McpStableIds.parseAgentToolId("").isPresent());
    assertFalse(
        McpStableIds.parseAgentToolId("other.abcdef0123456789abcdef0123456789").isPresent());
    assertFalse(McpStableIds.parseAgentToolId("mcp.short").isPresent());
    assertFalse(
        McpStableIds.parseAgentToolId("mcp.abcdef0123456789abcdef0123456789extra").isPresent());
    assertFalse(McpStableIds.parseAgentToolId("mcp.gggggggggggggggggggggggggggggggg").isPresent());
  }

  @Test
  void canonicalUuidFormattingAndParsing() {
    // 意图：验证 32 位无连字符 hex UUID 的序列化与解析
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
  }

  @Test
  void nullChecks() {
    // 意图：验证公共方法的非空检查
    assertThrows(NullPointerException.class, () -> McpStableIds.agentToolId(null));
    assertThrows(NullPointerException.class, () -> McpStableIds.localName(null));
    assertThrows(NullPointerException.class, () -> McpStableIds.canonicalUuid(null));
  }
}
