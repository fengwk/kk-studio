package fun.fengwk.kkstudio.harness.runtime.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code ask_user} 冻结问卷与答案的严格确定性 JSON codec。
 *
 * <p>问卷来自 Assistant ToolCall 的 arguments，是本契约唯一的冻结事实源：字段严格校验（未知字段、缺失必填、非法类型一律拒绝），
 * 从而保证接受时冻结的形状与提交时校验的形状完全一致。答案编码为模型与 history 都能读取的 canonical JSON。
 */
public final class HumanInputJsonCodec {

  private static final String QUESTIONNAIRE = HumanInputTool.ASK_USER;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> QUESTIONNAIRE_FIELDS = Set.of("questions");
  private static final Set<String> QUESTION_FIELDS = Set.of("question", "multiple", "options");
  private static final Set<String> OPTION_FIELDS = Set.of("label", "description", "recommended");

  /** 解析冻结问卷；任何与 {@code ask_user} 契约不符的输入抛 {@link IllegalArgumentException}。 */
  public HumanInputQuestionnaire decodeQuestionnaire(String argumentsJson) {
    JsonNode value = JsonValues.readTree(Objects.requireNonNull(argumentsJson, "argumentsJson"));
    ObjectNode root = requireObject(value, QUESTIONNAIRE);
    requireFields(root, QUESTIONNAIRE_FIELDS, QUESTIONNAIRE_FIELDS, QUESTIONNAIRE);
    ArrayNode questions = requireArray(root.get("questions"), QUESTIONNAIRE + ".questions");
    List<HumanInputQuestion> parsed = new ArrayList<>(questions.size());
    for (JsonNode question : questions) {
      parsed.add(decodeQuestion(question));
    }
    return new HumanInputQuestionnaire(parsed);
  }

  /** 编码规范化答案：{@code {"declined":true}} 或 {@code {"answers":[["a"],["b","c"]]}}。 */
  public String encodeAnswers(HumanInputAnswers answers) {
    Objects.requireNonNull(answers, "answers");
    ObjectNode node = NODES.objectNode();
    if (answers.declined()) {
      node.put("declined", true);
      return JsonValues.write(node);
    }
    ArrayNode questions = node.putArray("answers");
    for (List<String> questionAnswers : answers.answers()) {
      ArrayNode entries = questions.addArray();
      for (String answer : questionAnswers) {
        entries.add(answer);
      }
    }
    return JsonValues.write(node);
  }

  private static HumanInputQuestion decodeQuestion(JsonNode value) {
    String context = QUESTIONNAIRE + ".questions[]";
    ObjectNode node = requireObject(value, context);
    requireFields(node, QUESTION_FIELDS, Set.of("question"), context);
    String question = requireText(node.get("question"), context + ".question");
    boolean multiple = requireBoolean(node.get("multiple"), context + ".multiple", false);
    List<HumanInputOption> options = List.of();
    JsonNode declaredOptions = node.get("options");
    if (declaredOptions != null && !declaredOptions.isNull()) {
      ArrayNode array = requireArray(declaredOptions, context + ".options");
      List<HumanInputOption> parsed = new ArrayList<>(array.size());
      for (JsonNode option : array) {
        parsed.add(decodeOption(option));
      }
      options = parsed;
    }
    return new HumanInputQuestion(question, multiple, options);
  }

  private static HumanInputOption decodeOption(JsonNode value) {
    String context = QUESTIONNAIRE + ".questions[].options[]";
    ObjectNode node = requireObject(value, context);
    requireFields(node, OPTION_FIELDS, Set.of("label"), context);
    return new HumanInputOption(
        requireText(node.get("label"), context + ".label"),
        requireNullableText(node.get("description"), context + ".description"),
        requireBoolean(node.get("recommended"), context + ".recommended", false));
  }

  private static ObjectNode requireObject(JsonNode value, String context) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(context + " must be an object");
    }
    return (ObjectNode) value;
  }

  private static ArrayNode requireArray(JsonNode value, String context) {
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException(context + " must be an array");
    }
    return (ArrayNode) value;
  }

  private static String requireText(JsonNode value, String context) {
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(context + " must be text");
    }
    return value.textValue();
  }

  private static String requireNullableText(JsonNode value, String context) {
    if (value == null || value.isNull()) {
      return null;
    }
    return requireText(value, context);
  }

  private static boolean requireBoolean(JsonNode value, String context, boolean absent) {
    if (value == null) {
      return absent;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + " must be boolean");
    }
    return value.booleanValue();
  }

  /** 严格字段集合：拒绝未知字段，要求全部必填字段存在。 */
  private static void requireFields(
      ObjectNode node, Set<String> allowed, Set<String> required, String context) {
    Set<String> names = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    for (String name : names) {
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException(context + " must not declare " + name);
      }
    }
    for (String name : required) {
      if (!names.contains(name)) {
        throw new IllegalArgumentException(context + " must declare " + name);
      }
    }
  }
}
