package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime;

import fun.fengwk.convention4j.comfyui.workflow.Workflow;

import java.util.List;
import java.util.Optional;

/**
 * 已校验过的输入绑定模型，可供 runtime 提交时复用。
 *
 * <p>本类的所有字段都来自 {@link ComfyuiWorkflowApiBindingsParser}，对原始 JSON 进行了唯一性、kind/type 白名单、target node
 * 与 input 存在性的校验。运行期可以直接迭代 {@link #bindings()}，避免再次解析与校验。
 *
 * @author fengwk
 */
public final class ComfyuiWorkflowApiBindings {

  private final Workflow workflow;
  private final List<Binding> bindings;
  private final String defaultSelector;

  public ComfyuiWorkflowApiBindings(
      Workflow workflow, List<Binding> bindings, String defaultSelector) {
    this.workflow = workflow;
    this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
    this.defaultSelector = defaultSelector;
  }

  public Workflow workflow() {
    return workflow;
  }

  public List<Binding> bindings() {
    return bindings;
  }

  public String defaultSelector() {
    return defaultSelector;
  }

  /** 按 binding name 查找，用于 runtime 直接按名取值。 */
  public Optional<Binding> find(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (Binding binding : bindings) {
      if (name.equals(binding.name())) {
        return Optional.of(binding);
      }
    }
    return Optional.empty();
  }

  /** 输入绑定条目，值对象。 */
  public static final class Binding {

    private final String name;
    private final Kind kind;
    private final String nodeId;
    private final String inputName;
    private final boolean required;
    private final String description;
    private final ParameterOptions parameterOptions;

    public Binding(
        String name,
        Kind kind,
        String nodeId,
        String inputName,
        boolean required,
        String description,
        ParameterOptions parameterOptions) {
      this.name = name;
      this.kind = kind;
      this.nodeId = nodeId;
      this.inputName = inputName;
      this.required = required;
      this.description = description;
      this.parameterOptions = parameterOptions;
    }

    public String name() {
      return name;
    }

    public Kind kind() {
      return kind;
    }

    public String nodeId() {
      return nodeId;
    }

    public String inputName() {
      return inputName;
    }

    public boolean required() {
      return required;
    }

    public String description() {
      return description;
    }

    public ParameterOptions parameterOptions() {
      return parameterOptions;
    }
  }

  public enum Kind {
    PARAMETER,
    FILE
  }

  /** 仅 kind = PARAMETER 的绑定拥有该可选配置。 */
  public static final class ParameterOptions {

    private final ValueType valueType;
    private final Object defaultValue;

    public ParameterOptions(ValueType valueType, Object defaultValue) {
      this.valueType = valueType;
      this.defaultValue = defaultValue;
    }

    public ValueType valueType() {
      return valueType;
    }

    public Object defaultValue() {
      return defaultValue;
    }
  }

  public enum ValueType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    JSON
  }
}
