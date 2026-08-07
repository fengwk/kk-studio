package fun.fengwk.kkstudio.core.ai.runtime.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.goal.CreateGoalTool;
import fun.fengwk.kkstudio.harness.runtime.goal.GetGoalTool;
import fun.fengwk.kkstudio.harness.runtime.goal.GoalStore;
import fun.fengwk.kkstudio.harness.runtime.goal.UpdateGoalTool;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;

import java.time.Clock;
import java.util.Set;

/**
 * 把 runtime 工具（{@code load_skill}、goal 工具）装配为 Spring {@code Tool} bean，并为 {@code ToolFactories} 暴露为
 * {@link ToolFactory} bean。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolsConfiguration {

  @Bean
  @ConditionalOnBean(GoalStore.class)
  @ConditionalOnMissingBean
  public CreateGoalTool createGoalTool(GoalStore goalStore, Clock clock) {
    return new CreateGoalTool(goalStore, clock);
  }

  @Bean
  @ConditionalOnBean(GoalStore.class)
  @ConditionalOnMissingBean
  public GetGoalTool getGoalTool(GoalStore goalStore) {
    return new GetGoalTool(goalStore);
  }

  @Bean
  @ConditionalOnBean(GoalStore.class)
  @ConditionalOnMissingBean
  public UpdateGoalTool updateGoalTool(GoalStore goalStore, Clock clock) {
    return new UpdateGoalTool(goalStore, clock);
  }

  @Bean
  @ConditionalOnBean({ThreadSelectedSkillLookup.class, SkillBodyLoader.class})
  @ConditionalOnMissingBean
  public LoadSkillTool loadSkillTool(
      ThreadSelectedSkillLookup skillLookup, SkillBodyLoader skillBodyLoader) {
    return new LoadSkillTool(skillLookup, skillBodyLoader);
  }

  @Bean
  @ConditionalOnBean(CreateGoalTool.class)
  @ConditionalOnMissingBean(name = "createGoalToolFactory")
  public ToolFactory createGoalToolFactory(CreateGoalTool createGoalTool) {
    return ToolFactory.singleton(createGoalTool);
  }

  @Bean
  @ConditionalOnBean(GetGoalTool.class)
  @ConditionalOnMissingBean(name = "getGoalToolFactory")
  public ToolFactory getGoalToolFactory(GetGoalTool getGoalTool) {
    return ToolFactory.singleton(getGoalTool);
  }

  @Bean
  @ConditionalOnBean(UpdateGoalTool.class)
  @ConditionalOnMissingBean(name = "updateGoalToolFactory")
  public ToolFactory updateGoalToolFactory(UpdateGoalTool updateGoalTool) {
    return ToolFactory.singleton(updateGoalTool);
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
  public ToolCatalog toolCatalog(ToolFactories toolFactories) {
    return new ToolCatalog(toolFactories.descriptors(), Set.of(LoadSkillTool.NAME));
  }
}
