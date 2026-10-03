package fun.fengwk.kkstudio.platform.configsync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置同步 YAML 的读写边界。
 *
 * <p>读取走 SnakeYAML {@link SafeConstructor}：只接受标准对象/数组/标量，重复键、递归键、非法 tag 与任意 Java 类型一律拒绝；从不由
 * YAML 构造任意对象。输出只写纯 {@code Map}/{@code List}/标量，不产生 Java tag。错误信息绝不回显 YAML 片段或字段值。
 *
 * <p>嵌套结构到强类型 DTO 的转换使用独立严格 mapper（未知字段、重复键、浮点→整数、字符串→数字等宽松转换全部失败），保证
 * {@code protocolOptionsJson}、布尔与数值不会被静默改写。
 */
@Component
public final class ConfigSyncYaml {

  private static final String RESOURCE = "config_sync";
  private static final int MAX_CODE_POINTS = 8 * 1024 * 1024;
  private static final int MAX_ALIASES = 50;

  private final Yaml reader;
  private final Yaml writer;
  private final ObjectMapper strictMapper;

  public ConfigSyncYaml(ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    loaderOptions.setAllowRecursiveKeys(false);
    loaderOptions.setMaxAliasesForCollections(MAX_ALIASES);
    loaderOptions.setCodePointLimit(MAX_CODE_POINTS);
    this.reader = new Yaml(new SafeConstructor(loaderOptions));
    DumperOptions dumperOptions = new DumperOptions();
    dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    dumperOptions.setPrettyFlow(true);
    dumperOptions.setSplitLines(false);
    this.writer = new Yaml(dumperOptions);
    ObjectMapper strict = objectMapper.copy();
    strict.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    strict.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    strict.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    this.strictMapper = strict;
  }

  /** 解析 YAML 文档顶层为纯字符串键 Map；任何结构或类型问题都作为导入错误抛出。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> parse(String yaml) {
    if (yaml == null || yaml.isBlank()) {
      throw new AiValidationException(RESOURCE, "config yaml must not be blank");
    }
    Object root;
    try {
      root = reader.load(yaml);
    } catch (RuntimeException error) {
      // 不回显 SnakeYAML 的错误文本：其中可能包含 YAML 片段或键值。
      throw new AiValidationException(RESOURCE, "config yaml is not a valid YAML document");
    }
    if (!(root instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      throw new AiValidationException(RESOURCE, "config yaml root must be a non-empty object");
    }
    Map<String, Object> document = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new AiValidationException(RESOURCE, "config yaml keys must be strings");
      }
      document.put(key, normalize(entry.getValue(), key));
    }
    return document;
  }

  /** 将纯结构文档输出为 YAML 文本；不产生 Java tag。 */
  public String dump(Map<String, Object> document) {
    return writer.dump(document);
  }

  /** 把已解析的纯结构节点转换为强类型 DTO；失败信息只包含路径，不回显值。 */
  public <T> T convert(Object node, Class<T> type, String path) {
    if (node == null) {
      throw new AiValidationException(RESOURCE, path + " must not be null");
    }
    try {
      return strictMapper.convertValue(node, type);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, path + " is not a valid value");
    }
  }

  private Object normalize(Object value, String path) {
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number || value instanceof Character) {
      return value;
    }
    if (value instanceof Map<?, ?> rawMap) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new AiValidationException(RESOURCE, path + " keys must be strings");
        }
        normalized.put(key, normalize(entry.getValue(), path + "." + key));
      }
      return normalized;
    }
    if (value instanceof List<?> rawList) {
      List<Object> normalized = new ArrayList<>(rawList.size());
      for (int index = 0; index < rawList.size(); index++) {
        normalized.add(normalize(rawList.get(index), path + "[" + index + "]"));
      }
      return normalized;
    }
    // SafeConstructor 理论上不会产生其它类型；出现即视为非法输入。
    throw new AiValidationException(RESOURCE, path + " has an unsupported YAML value");
  }

  /** 供需要序列化 DTO 到纯结构的调用方复用同一个严格 mapper 的写能力。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> toMap(Object value, String path) {
    try {
      return strictMapper.convertValue(value, LinkedHashMap.class);
    } catch (IllegalArgumentException | ClassCastException error) {
      throw new IllegalStateException("cannot serialize " + path, error);
    }
  }

  /** 供导出侧把 JSON 文本解析成纯结构；失败属于内部数据损坏。 */
  public Object readJson(String json) {
    try {
      return strictMapper.readValue(json, Object.class);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot read stored json", error);
    }
  }
}
