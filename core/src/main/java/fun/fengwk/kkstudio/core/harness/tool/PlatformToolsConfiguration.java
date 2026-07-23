package fun.fengwk.kkstudio.core.harness.tool;

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

import java.time.Clock;

/**
 * Wires platform tools ({@code load_skill}, goal tools) as Spring {@code Tool} beans for {@code
 * CoreHarnessExtension} collection.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformToolsConfiguration {

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
}
