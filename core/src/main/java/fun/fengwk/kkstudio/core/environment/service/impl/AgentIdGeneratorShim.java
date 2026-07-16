package fun.fengwk.kkstudio.core.environment.service.impl;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;

/**
 * Static indirection so {@link ToolEnvironmentMutationFactory} can be unit-tested without Spring.
 */
final class AgentIdGeneratorShim {

  private AgentIdGeneratorShim() {}

  static long nextToolEnvironmentId() {
    return AgentIdGenerator.nextToolEnvironmentId();
  }
}
