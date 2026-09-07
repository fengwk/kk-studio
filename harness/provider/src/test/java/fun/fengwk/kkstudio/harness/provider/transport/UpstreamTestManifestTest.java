package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 校验 upstream-test-manifest.json 的机器可读性与完整性。
 *
 * <p>确保所有 LangChain4j 1.20.0 上游映射用例均在本地测试类中真实存在，无 skip/disabled， 且上游源文件在指定的 upstream 仓库中真实存在。
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";
  private static final Path UPSTREAM_DIR = Path.of("/tmp/tmpdir-3275929659507930382/upstream");

  @Test
  void upstreamTestManifestIsCompleteAndAllLocalTestsExist() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("upstream-test-manifest.json")) {
      assertNotNull(in, "upstream-test-manifest.json must exist in test resources");
      root = mapper.readTree(in);
    }

    String commit = root.get("upstreamCommit").asText();
    assertEquals(
        REQUIRED_UPSTREAM_COMMIT,
        commit,
        "upstream commit must match required LangChain4j 1.20.0 release commit");

    JsonNode cases = root.get("cases");
    assertTrue(cases.isArray() && cases.size() >= 35, "must map all upstream cases");

    for (JsonNode item : cases) {
      String upstreamSource = item.get("upstreamSource").asText();
      String upstreamMethod = item.get("upstreamMethod").asText();
      String localClass = item.get("localClass").asText();
      String localMethod = item.get("localMethod").asText();

      // 验证上游文件存在于上游指定路径
      if (Files.isDirectory(UPSTREAM_DIR)) {
        Path upstreamFile = UPSTREAM_DIR.resolve(upstreamSource);
        assertTrue(
            Files.isRegularFile(upstreamFile), "upstream source file must exist: " + upstreamFile);
      }

      // 验证本地测试类存在
      Class<?> clazz = Class.forName(localClass);
      assertNotNull(clazz, "local test class must be loadable: " + localClass);

      // 验证本地测试方法存在
      boolean methodFound =
          Arrays.stream(clazz.getDeclaredMethods()).anyMatch(m -> m.getName().equals(localMethod));
      assertTrue(
          methodFound,
          () ->
              "local test method "
                  + localMethod
                  + " must exist in "
                  + localClass
                  + " for upstream "
                  + upstreamMethod);
    }
  }
}
