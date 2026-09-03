package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 聚合多个 {@link RuntimeToolCatalog} 源的复合运行时工具目录。
 *
 * <p>按委托目录声明顺序聚合工具，不进行任何缓存。当跨源（或同源）出现重复的 {@link AgentToolId} 或模型可见工具名时， {@link #selectableTools()}
 * 与 {@link #findTool(AgentToolId)} 均执行 fail-closed 策略抛出确定性异常， 绝不静默选择其中一个。构造期拒绝重复的 catalog 实例。
 */
public final class CompositeRuntimeToolCatalog implements RuntimeToolCatalog {

  private final List<RuntimeToolCatalog> delegates;

  public CompositeRuntimeToolCatalog(List<? extends RuntimeToolCatalog> delegates) {
    Objects.requireNonNull(delegates, "delegates");
    Set<RuntimeToolCatalog> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (RuntimeToolCatalog delegate : delegates) {
      Objects.requireNonNull(delegate, "delegate catalog must not be null");
      if (!seen.add(delegate)) {
        throw new IllegalArgumentException("duplicate runtime tool catalog source instance");
      }
    }
    this.delegates = List.copyOf(delegates);
  }

  public CompositeRuntimeToolCatalog(RuntimeToolCatalog... delegates) {
    this(Arrays.asList(Objects.requireNonNull(delegates, "delegates")));
  }

  @Override
  public List<ToolContribution> selectableTools() {
    return List.copyOf(collectAndValidateSelectables().values());
  }

  @Override
  public Optional<ToolContribution> findTool(AgentToolId id) {
    Objects.requireNonNull(id, "id");
    Map<AgentToolId, ToolContribution> selectables = collectAndValidateSelectables();
    ToolContribution selectableMatch = selectables.get(id);
    if (selectableMatch != null) {
      return Optional.of(selectableMatch);
    }
    ToolContribution internalMatch = null;
    for (RuntimeToolCatalog delegate : delegates) {
      Optional<ToolContribution> candidate = delegate.findTool(id);
      if (candidate.isPresent()) {
        if (internalMatch != null) {
          throw new IllegalStateException("duplicate AgentToolId across catalogs: " + id);
        }
        internalMatch = candidate.get();
      }
    }
    if (internalMatch == null) {
      return Optional.empty();
    }
    String internalModelName = internalMatch.definition().descriptor().name();
    for (ToolContribution tool : selectables.values()) {
      if (!tool.definition().id().equals(internalMatch.definition().id())
          && tool.definition().descriptor().name().equals(internalModelName)) {
        throw new IllegalStateException(
            "duplicate tool model name across catalogs: " + internalModelName);
      }
    }
    return Optional.of(internalMatch);
  }

  private Map<AgentToolId, ToolContribution> collectAndValidateSelectables() {
    Map<AgentToolId, ToolContribution> byId = new LinkedHashMap<>();
    Map<String, ToolContribution> byModelName = new LinkedHashMap<>();
    for (RuntimeToolCatalog delegate : delegates) {
      for (ToolContribution tool : delegate.selectableTools()) {
        AgentToolId toolId = tool.definition().id();
        String modelName = tool.definition().descriptor().name();

        ToolContribution existingId = byId.putIfAbsent(toolId, tool);
        if (existingId != null) {
          throw new IllegalStateException("duplicate AgentToolId across catalogs: " + toolId);
        }

        ToolContribution existingName = byModelName.putIfAbsent(modelName, tool);
        if (existingName != null) {
          throw new IllegalStateException(
              "duplicate tool model name across catalogs: " + modelName);
        }
      }
    }
    return byId;
  }
}
