package fun.fengwk.kkstudio.agent.provider.fixtures;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ProviderTestFixtures 负责加载 provider 测试所需的 JSON fixture。
 *
 * @author fengwk
 */
public final class ProviderTestFixtures {

  private static final String LIVE_CASES_RESOURCE =
      "fun/fengwk/kkstudio/agent/provider/fixtures/live-cases.json";
  private static final String CONTRACT_CASES_RESOURCE =
      "fun/fengwk/kkstudio/agent/provider/fixtures/contract-cases.json";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Map<String, ProviderLiveCase> LIVE_CASES =
      loadCases(
          LIVE_CASES_RESOURCE,
          new TypeReference<List<ProviderLiveCase>>() {},
          ProviderLiveCase::name);
  private static final Map<String, ProviderContractCase> CONTRACT_CASES =
      loadCases(
          CONTRACT_CASES_RESOURCE,
          new TypeReference<List<ProviderContractCase>>() {},
          ProviderContractCase::providerName);

  private ProviderTestFixtures() {}

  /** 按名称返回一个 live fixture。 */
  public static ProviderLiveCase liveCase(String name) {
    ProviderLiveCase liveCase = LIVE_CASES.get(name);
    if (liveCase == null) {
      throw new IllegalArgumentException("provider live case not found: " + name);
    }
    return liveCase;
  }

  /** 按 providerName 返回一个 HTTP contract fixture。 */
  public static ProviderContractCase contractCase(String providerName) {
    ProviderContractCase contractCase = CONTRACT_CASES.get(providerName);
    if (contractCase == null) {
      throw new IllegalArgumentException("provider contract case not found: " + providerName);
    }
    return contractCase;
  }

  /** 从 JSON 资源加载一组 fixture，并按指定主键组织为 map。 */
  private static <T> Map<String, T> loadCases(
      String resourcePath, TypeReference<List<T>> typeReference, Function<T, String> keyFunction) {
    try (InputStream inputStream =
        ProviderTestFixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IllegalStateException("fixture resource not found: " + resourcePath);
      }
      List<T> cases = OBJECT_MAPPER.readValue(inputStream, typeReference);
      Map<String, T> result = new LinkedHashMap<>();
      for (T fixtureCase : cases) {
        result.put(keyFunction.apply(fixtureCase), fixtureCase);
      }
      return result;
    } catch (IOException e) {
      throw new IllegalStateException("failed to load fixture resource: " + resourcePath, e);
    }
  }

  public record ProviderLiveCase(
      String name,
      String prompt,
      boolean requiresTool,
      String expectedTextContains,
      boolean expectToolCall,
      String expectedToolName,
      String expectedToolArgumentsContains) {}

  /** ProviderContractCase 描述一个 provider 的本地 HTTP 契约测试场景。 */
  public record ProviderContractCase(
      String providerName,
      String baseUrlPath,
      String expectedPath,
      String authHeaderName,
      String authHeaderValuePrefix,
      List<String> requiredHeaderNames,
      String prompt,
      boolean requiresTool,
      List<String> bodyContains,
      int responseStatus,
      String responseContentType,
      String responseBody) {}
}
