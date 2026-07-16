package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link ComfyuiWorkflowApiBindingsParser} 的输入合同校验测试。
 *
 * <p>覆盖目标：
 *
 * <ul>
 *   <li>workflowJson 必须可被 {@code Workflow.fromApiJson} 解析；
 *   <li>inputBindingsJson 的字段必备/唯一/kind 白名单/target 输入存在性；
 *   <li>parameter / file 各自的子字段约束；
 *   <li>defaultSelector 静态规则（长度、descend、regex filter、JsonPath compile）。
 * </ul>
 *
 * @author fengwk
 */
public class ComfyuiWorkflowApiBindingsParserTest {

  /** 一个最简且合法的 ComfyUI API 格式 workflow，节点 3 上有 seed/noise_seed 输入。 */
  private static final String WORKFLOW_JSON =
      "{\n"
          + "  \"3\": {\"class_type\":\"KSampler\",\"inputs\":{\"seed\":0,\"noise_seed\":1}}\n"
          + "}";

  private final ComfyuiWorkflowApiBindingsParser parser =
      new ComfyuiWorkflowApiBindingsParser(new ObjectMapper());

  @Test
  public void shouldParseParameterBindingAndSelector() {
    String bindings =
        "[\n"
            + "  {\"name\":\"seed\",\"kind\":\"parameter\",\"nodeId\":\"3\","
            + "\"inputName\":\"seed\",\"required\":true,\"description\":\"rng\","
            + "\"valueType\":\"integer\",\"defaultValue\":42}\n"
            + "]";
    ComfyuiWorkflowApiBindings parsed = parser.parse(WORKFLOW_JSON, bindings, "$['3'].inputs.seed");
    assertEquals(1, parsed.bindings().size());
    ComfyuiWorkflowApiBindings.Binding b = parsed.bindings().get(0);
    assertEquals("seed", b.name());
    assertEquals(ComfyuiWorkflowApiBindings.Kind.PARAMETER, b.kind());
    assertEquals("3", b.nodeId());
    assertEquals("seed", b.inputName());
    assertTrue(b.required());
    assertEquals("rng", b.description());
    assertEquals(ComfyuiWorkflowApiBindings.ValueType.INTEGER, b.parameterOptions().valueType());
    assertEquals(42L, b.parameterOptions().defaultValue());
    assertEquals("$['3'].inputs.seed", parsed.defaultSelector());
  }

  @Test
  public void shouldParseFileBinding() {
    String bindings =
        "[{\"name\":\"ref\",\"kind\":\"file\",\"nodeId\":\"3\"," + "\"inputName\":\"noise_seed\"}]";
    ComfyuiWorkflowApiBindings parsed = parser.parse(WORKFLOW_JSON, bindings, null);
    assertNull(parsed.defaultSelector());
    ComfyuiWorkflowApiBindings.Binding b = parsed.bindings().get(0);
    assertEquals(ComfyuiWorkflowApiBindings.Kind.FILE, b.kind());
    assertNull(b.parameterOptions());
  }

  @Test
  public void shouldParseKindAndValueTypeIndependentlyOfDefaultLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      String bindings =
          "[{\"name\":\"ref\",\"kind\":\"file\",\"nodeId\":\"3\",\"inputName\":\"noise_seed\"},"
              + "{\"name\":\"seed\",\"kind\":\"parameter\",\"nodeId\":\"3\","
              + "\"inputName\":\"seed\",\"valueType\":\"integer\"}]";

      ComfyuiWorkflowApiBindings parsed = parser.parse(WORKFLOW_JSON, bindings, null);

      assertEquals(ComfyuiWorkflowApiBindings.Kind.FILE, parsed.bindings().get(0).kind());
      assertEquals(
          ComfyuiWorkflowApiBindings.ValueType.INTEGER,
          parsed.bindings().get(1).parameterOptions().valueType());
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  public void shouldRejectDuplicateBindingName() {
    String bindings =
        "[{\"name\":\"seed\",\"kind\":\"parameter\",\"nodeId\":\"3\",\"inputName\":\"seed\"},"
            + "{\"name\":\"seed\",\"kind\":\"parameter\",\"nodeId\":\"3\",\"inputName\":\"noise_seed\"}"
            + "]";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, bindings, null));
    assertTrue(ex.getMessage().contains("duplicate"));
  }

  @Test
  public void shouldRejectUnknownKindAndType() {
    String badKind =
        "[{\"name\":\"x\",\"kind\":\"weird\",\"nodeId\":\"3\",\"inputName\":\"seed\"}]";
    assertThrows(IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, badKind, null));

    String badType =
        "[{\"name\":\"x\",\"kind\":\"parameter\",\"nodeId\":\"3\","
            + "\"inputName\":\"seed\",\"valueType\":\"decimal\"}]";
    assertThrows(IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, badType, null));
  }

  @Test
  public void shouldRejectUnknownNodeAndMissingInput() {
    String unknownNode =
        "[{\"name\":\"x\",\"kind\":\"parameter\"," + "\"nodeId\":\"99\",\"inputName\":\"seed\"}]";
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, unknownNode, null));

    String unknownInput =
        "[{\"name\":\"x\",\"kind\":\"parameter\"," + "\"nodeId\":\"3\",\"inputName\":\"prompt\"}]";
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, unknownInput, null));
  }

  @Test
  public void shouldRejectInvalidWorkflowJson() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not json", "[]", null));
    assertTrue(ex.getMessage().contains("workflowJson"));
  }

  @Test
  public void shouldRejectNonArrayBindings() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, "{}", null));
  }

  @Test
  public void shouldRejectParameterOnlyFieldsOnFileBinding() {
    String defaultBind =
        "[{\"name\":\"r\",\"kind\":\"file\",\"nodeId\":\"3\","
            + "\"inputName\":\"noise_seed\",\"defaultValue\":\"inputs/ref.png\"}]";
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, defaultBind, null));

    String typedBind =
        "[{\"name\":\"r\",\"kind\":\"file\",\"nodeId\":\"3\","
            + "\"inputName\":\"noise_seed\",\"valueType\":\"string\"}]";
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, typedBind, null));
  }

  @Test
  public void shouldRejectSelectorWithDescentOrRegexFilter() {
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, "[]", "$..seed"));
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse(WORKFLOW_JSON, "[]", "$[?(@.name =~ '.*')]"));
  }

  @Test
  public void shouldRejectSelectorLongerThanMax() {
    String tooLong = "$." + "a".repeat(ComfyuiWorkflowApiSelectorValidator.MAX_SELECTOR_LENGTH);
    assertThrows(IllegalArgumentException.class, () -> parser.parse(WORKFLOW_JSON, "[]", tooLong));
  }
}
