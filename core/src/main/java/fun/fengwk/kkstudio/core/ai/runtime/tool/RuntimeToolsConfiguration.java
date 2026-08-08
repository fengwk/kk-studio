package fun.fengwk.kkstudio.core.ai.runtime.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.ToolContribution;
import fun.fengwk.kkstudio.harness.plugin.ToolVisibility;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把普通 runtime 工具（当前为 {@code load_skill}）装配为 Spring {@code Tool} bean，并为 {@code ToolFactories} 暴露为
 * {@link ToolFactory} bean；插件 Tool 由冻结 {@link PluginCatalog} 独立贡献。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolsConfiguration {

  @Bean
  @ConditionalOnBean({ThreadSelectedSkillLookup.class, SkillBodyLoader.class})
  @ConditionalOnMissingBean
  public LoadSkillTool loadSkillTool(
      ThreadSelectedSkillLookup skillLookup, SkillBodyLoader skillBodyLoader) {
    return new LoadSkillTool(skillLookup, skillBodyLoader);
  }

  @Bean
  @ConditionalOnBean(LoadSkillTool.class)
  @ConditionalOnMissingBean(name = "loadSkillToolFactory")
  public ToolFactory loadSkillToolFactory(LoadSkillTool loadSkillTool) {
    return ToolFactory.singleton(loadSkillTool);
  }

  @Bean
  public ToolFactories toolFactories(ObjectProvider<ToolFactory> toolFactoryBeans) {
    return new ToolFactories(toolFactoryBeans.orderedStream().toList());
  }

  @Bean
  public ToolCatalog toolCatalog(ToolFactories toolFactories, PluginCatalog pluginCatalog) {
    List<ToolDescriptor> descriptors = new ArrayList<>(toolFactories.descriptors());
    Set<String> internalNames = new HashSet<>(Set.of(LoadSkillTool.NAME));
    for (ToolContribution contribution : pluginCatalog.tools()) {
      descriptors.add(contribution.descriptor());
      if (contribution.visibility() == ToolVisibility.INTERNAL) {
        internalNames.add(contribution.descriptor().name());
      }
    }
    return new ToolCatalog(descriptors, internalNames);
  }
}
