package fun.fengwk.kkstudio.harness.plugin.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 严格解析、不可变的 prompt 模板：只支持 {@code ${name}} 占位符，无其它模板语法与转义。
 *
 * <p>构造时解析并拒绝：缺失占位符资源（由 loader 负责）、未闭合 {@code ${}；非法变量名。渲染时拒绝：模板变量未提供
 * （unresolved）或提供了模板之外的变量（unexpected）；变量值按原样替换，不做二次解析。变量名必须是
 * {@code [A-Za-z_][A-Za-z0-9_]*}。
 */
public final class PromptTemplate {

  private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final String resourceName;
  private final String raw;
  private final List<String> variables;

  PromptTemplate(String resourceName, String raw) {
    this.resourceName = Objects.requireNonNull(resourceName, "resourceName");
    this.raw = Objects.requireNonNull(raw, "raw");
    this.variables = parseVariables(raw);
  }

  /** 模板来源资源名。 */
  public String resourceName() {
    return resourceName;
  }

  /** 缓存的原始资源文本。 */
  public String raw() {
    return raw;
  }

  /** 模板声明的变量名，按首次出现顺序去重。 */
  public List<String> variables() {
    return variables;
  }

  /** 渲染模板：必须恰好提供模板声明的全部变量（unresolved / unexpected 都拒绝），占位符按原样替换为变量值。 */
  public String render(Map<String, String> values) {
    Objects.requireNonNull(values, "values");
    List<String> declared = variables;
    for (String name : declared) {
      if (!values.containsKey(name) || values.get(name) == null) {
        throw new IllegalArgumentException(
            "unresolved placeholder ${" + name + "} in prompt template " + resourceName);
      }
    }
    for (String name : values.keySet()) {
      if (!declared.contains(name)) {
        throw new IllegalArgumentException(
            "unexpected variable " + name + " for prompt template " + resourceName);
      }
    }
    StringBuilder result = new StringBuilder(raw.length());
    int cursor = 0;
    while (true) {
      int open = raw.indexOf("${", cursor);
      if (open < 0) {
        result.append(raw, cursor, raw.length());
        break;
      }
      int close = raw.indexOf('}', open + 2);
      if (close < 0) {
        // 构造时已拒绝未闭合占位符；此处不可能到达。
        throw new IllegalStateException(
            "unterminated placeholder in prompt template " + resourceName);
      }
      String name = raw.substring(open + 2, close);
      result.append(raw, cursor, open);
      result.append(values.get(name));
      cursor = close + 1;
    }
    return result.toString();
  }

  private static List<String> parseVariables(String raw) {
    List<String> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int cursor = 0;
    while (true) {
      int open = raw.indexOf("${", cursor);
      if (open < 0) {
        return List.copyOf(result);
      }
      int close = raw.indexOf('}', open + 2);
      if (close < 0) {
        throw new IllegalArgumentException(
            "unterminated placeholder ${ starting at offset " + open + " in prompt template");
      }
      String name = raw.substring(open + 2, close);
      if (!VARIABLE_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "invalid placeholder variable name ${" + name + "} in prompt template");
      }
      if (seen.add(name)) {
        result.add(name);
      }
      cursor = close + 1;
    }
  }
}
