package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;

import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;

final class SingleAgentRegistry implements AgentRegistry {

  private final AgentInfo agentInfo;

  SingleAgentRegistry(AgentInfo agentInfo) {
    this.agentInfo = agentInfo;
  }

  @Override
  public void registerAgent(AgentInfo agentInfo) {
    throw new UnsupportedOperationException("registerAgent is not supported");
  }

  @Override
  public AgentInfo getAgent(String name) {
    return agentInfo != null && agentInfo.getName().equals(name) ? agentInfo : null;
  }
}
