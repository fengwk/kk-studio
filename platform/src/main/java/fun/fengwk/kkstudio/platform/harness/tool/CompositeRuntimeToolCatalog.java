package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
 * <p>按委托目录声明顺序聚合工具，不进行任何缓存。工具身份就是模型可见 name，因此同名即同一身份：当跨源（或同源）出现重复 name 时，{@link #selectableTools()}
 * 与 {@link #findTool(String)} 均 fail-closed 抛出确定性异常，绝不静默选择其中一个。 构造期拒绝重复的 catalog 实例。
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
    List<ToolContribution> selectable = new ArrayList<>();
    for (Map<String, ToolContribution> index : selectableIndexes()) {
      selectable.addAll(index.values());
    }
    return List.copyOf(selectable);
  }

  @Override
  public Optional<ToolContribution> findTool(String toolName) {
    Objects.requireNonNull(toolName, "toolName");
    // 先整体校验可选工具：即使查询未知名称，已有同名冲突也必须先被暴露。
    List<Map<String, ToolContribution>> indexes = selectableIndexes();
    ToolContribution match = null;
    int claimants = 0;
    for (int i = 0; i < delegates.size(); i++) {
      // 同一 delegate 只是同一个来源，重复出现一律只算一次声明。
      ToolContribution claim = indexes.get(i).get(toolName);
      if (claim == null) {
        claim = delegates.get(i).findTool(toolName).orElse(null);
      }
      if (claim == null) {
        continue;
      }
      claimants++;
      match = claim;
    }
    if (claimants > 1) {
      // 与 ContributionId 是否一致无关：两个来源声明同一 name 就是重复身份。
      throw new IllegalStateException("duplicate tool name across catalogs: " + toolName);
    }
    return Optional.ofNullable(match);
  }

  /** 按 delegate 顺序返回各源的 SELECTABLE 名称索引，同时校验跨源与同源的名称唯一性。 */
  private List<Map<String, ToolContribution>> selectableIndexes() {
    List<Map<String, ToolContribution>> indexes = new ArrayList<>(delegates.size());
    Set<String> declaredNames = new HashSet<>();
    for (RuntimeToolCatalog delegate : delegates) {
      Map<String, ToolContribution> index = new LinkedHashMap<>();
      for (ToolContribution tool : delegate.selectableTools()) {
        String toolName = tool.definition().descriptor().name();
        if (!declaredNames.add(toolName)) {
          throw new IllegalStateException("duplicate tool name across catalogs: " + toolName);
        }
        index.put(toolName, tool);
      }
      indexes.add(index);
    }
    return indexes;
  }
}
