package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变 collection of {@link ToolFactory}，按 {@code (name, version)} 索引。
 *
 * <p>同一对 (name, version) 有两条注册时立刻以 IllegalArgumentException 失败；调用方可以信赖"重复 tool、null
 * descriptor"在构造阶段就被拒绝。descriptor mismatch 在 {@link #find(String, String)} 阶段被拒绝。
 */
public final class ToolFactories {

  private final List<ToolDescriptor> descriptors;
  private final Map<Key, ToolFactory> byKey;

  public ToolFactories(Collection<? extends ToolFactory> factories) {
    Objects.requireNonNull(factories, "factories");
    Map<Key, ToolFactory> index = new LinkedHashMap<>();
    List<ToolDescriptor> collectedDescriptors = new ArrayList<>(factories.size());
    for (ToolFactory factory : factories) {
      Objects.requireNonNull(factory, "factory");
      ToolDescriptor descriptor =
          Objects.requireNonNull(factory.descriptor(), "factory.descriptor");
      Key key = new Key(descriptor.name(), descriptor.version());
      if (index.putIfAbsent(key, factory) != null) {
        throw new IllegalArgumentException(
            "duplicate ToolFactory for " + descriptor.name() + "@" + descriptor.version());
      }
      collectedDescriptors.add(descriptor);
    }
    this.byKey = Map.copyOf(index);
    this.descriptors = List.copyOf(collectedDescriptors);
  }

  /** 注册顺序的冻结 descriptor 列表，供 resolver/validator 校验 tool name。 */
  public List<ToolDescriptor> descriptors() {
    return descriptors;
  }

  /** ToolRegistry 友好查询：未注册即返回 {@link Optional#empty()}；描述不匹配时立即失败。 */
  public Optional<Tool> find(String name, String version) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    Key key = new Key(name, version);
    ToolFactory factory = byKey.get(key);
    if (factory == null) {
      return Optional.empty();
    }
    Tool tool = Objects.requireNonNull(factory.create(), "ToolFactory.create");
    ToolDescriptor actual = Objects.requireNonNull(tool.descriptor(), "created tool descriptor");
    if (!key.name.equals(actual.name()) || !key.version.equals(actual.version())) {
      throw new IllegalArgumentException(
          "created tool descriptor "
              + actual.name()
              + "@"
              + actual.version()
              + " does not match registered "
              + key.name
              + "@"
              + key.version);
    }
    return Optional.of(tool);
  }

  /** (name, version) 主键。 */
  private record Key(String name, String version) {

    public Key {
      name = Objects.requireNonNull(name, "name");
      version = Objects.requireNonNull(version, "version");
    }
  }
}
