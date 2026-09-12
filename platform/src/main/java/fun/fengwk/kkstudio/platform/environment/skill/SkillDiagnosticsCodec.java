package fun.fengwk.kkstudio.platform.environment.skill;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code environment_skill_source.diagnostics} jsonb 列的严格、确定性 codec。
 *
 * <p>这是持久化边界而不是容错读路径：只接受恰好 {@code location}/{@code message} 两个非空文本字段的 JSON array，拒绝未知字段、 重复键、尾随内容、非
 * array 顶层与错误类型。异常只报告结构性问题，不回显诊断文本或任何配置原值。
 *
 * <p>与 {@code SystemSettingsCodec} 一样使用无参构造与独立 strict mapper：持久化格式不继承 HTTP 序列化的定制。
 */
@Component
public final class SkillDiagnosticsCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final String LOCATION = "location";
  private static final String MESSAGE = "message";

  /** 编码为 canonical JSON array；空诊断编码为 {@code []}。 */
  public String encode(List<DaemonSkillDiagnostic> diagnostics) {
    Objects.requireNonNull(diagnostics, "diagnostics");
    ArrayNode array = MAPPER.createArrayNode();
    for (DaemonSkillDiagnostic diagnostic : diagnostics) {
      Objects.requireNonNull(diagnostic, "diagnostics[]");
      ObjectNode node = array.addObject();
      node.put(LOCATION, diagnostic.location());
      node.put(MESSAGE, diagnostic.message());
    }
    try {
      return MAPPER.writeValueAsString(array);
    } catch (JsonProcessingException unused) {
      throw new IllegalArgumentException("cannot encode skill diagnostics");
    }
  }

  /** 解码 canonical JSON array；任何结构偏差都失败而不是静默丢弃。 */
  public List<DaemonSkillDiagnostic> decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException unused) {
      throw new IllegalArgumentException("malformed skill diagnostics");
    }
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException("skill diagnostics must be a JSON array");
    }
    List<DaemonSkillDiagnostic> diagnostics = new ArrayList<>(array.size());
    for (JsonNode element : array) {
      if (!(element instanceof ObjectNode node)) {
        throw new IllegalArgumentException("skill diagnostics entries must be JSON objects");
      }
      var fields = node.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!LOCATION.equals(field) && !MESSAGE.equals(field)) {
          throw new IllegalArgumentException("unexpected skill diagnostic field");
        }
      }
      diagnostics.add(
          new DaemonSkillDiagnostic(requiredText(node, LOCATION), requiredText(node, MESSAGE)));
    }
    return List.copyOf(diagnostics);
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("skill diagnostic." + field + " must be non-blank text");
    }
    return value.textValue();
  }
}
