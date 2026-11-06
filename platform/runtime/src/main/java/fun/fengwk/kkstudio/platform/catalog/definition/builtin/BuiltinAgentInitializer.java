package fun.fengwk.kkstudio.platform.catalog.definition.builtin;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentType;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;

/**
 * 启动期以 add-if-missing 方式初始化系统内置 Agent。
 *
 * <p>已存在的同名行是权威事实，绝不覆盖用户对 prompt/execution/tools/skills 的编辑；初始 execution 为 null。多实例并发启动时依赖
 * PostgreSQL 主键冲突收敛：冲突即视为其它实例已完成初始化，既有行仍是权威事实。
 */
@Component
@AllArgsConstructor
public class BuiltinAgentInitializer implements InitializingBean {

  private final AgentRepository agentRepository;
  private final AgentConfigCodec configCodec;

  @Override
  public void afterPropertiesSet() {
    for (BuiltinAgents.BuiltinAgent definition : BuiltinAgents.definitions()) {
      if (agentRepository.getByName(definition.name()) != null) {
        continue;
      }
      Agent builtin =
          Agent.builder()
              .name(definition.name())
              .type(AgentType.BUILTIN)
              .systemPrompt(definition.systemPrompt())
              .execution(null)
              .configJson(configCodec.encode(definition.config()))
              .build();
      try {
        agentRepository.create(builtin);
      } catch (DuplicateKeyException ignored) {
        // 并发初始化：其它实例已插入同名行；不覆盖既有定义。
      }
    }
  }
}
