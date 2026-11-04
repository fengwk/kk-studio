package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.net.URI;
import java.util.UUID;

class MiniMaxMavisResourceUriTest {

  /** 顶层与嵌套数组中的 vfs: 资源都经网关授权解析，其他取值原样保留。 */
  @Test
  void resolvesNestedVfsResourcesAndKeepsPlainValues() {
    PluginResourceGateway gateway = mock(PluginResourceGateway.class);
    MiniMaxMavisResourceAccess access = new MiniMaxMavisResourceAccess(gateway);
    JsonMapper mapper = JsonMapper.builder().build();
    UUID session = UUID.fromString("11111111-2222-3333-4444-555555555555");
    String id = "12345678-1234-1234-1234-123456789abc";
    String resource = "vfs:/resources/" + id;
    when(gateway.resolveSessionResource(session, resource))
        .thenReturn(URI.create("https://example.com/blob"));

    JsonNode resolved =
        access.resolveInputs(
            session,
            mapper.readTree(
                "{\"input\":[[\"" + resource + "\"]],\"plain\":\"https://example.com/a.png\"}"));

    assertEquals("https://example.com/blob", resolved.get("input").get(0).get(0).textValue());
    assertEquals("https://example.com/a.png", resolved.get("plain").textValue());
    verify(gateway).resolveSessionResource(session, resource);
  }
}
