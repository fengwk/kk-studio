package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class InMemoryUserRequestQueue implements UserRequestQueue {

  private final List<UserRequest> requests = new ArrayList<>();

  @Override
  public void submit(UserRequest userRequest) {
    requests.add(userRequest);
  }

  @Override
  public List<UserRequest> pollAll() {
    List<UserRequest> copied = List.copyOf(requests);
    requests.clear();
    return copied;
  }

  @Override
  public boolean isEmpty() {
    return requests.isEmpty();
  }
}

final class RecordingAgentEventHandler implements AgentEventHandler {

  private SessionEventType lastEventType;

  @Override
  public void onEvent(SessionEvent event) {
    if (event != null) {
      lastEventType = event.getEventType();
    }
  }

  boolean isTerminalFailure() {
    return lastEventType == SessionEventType.assistant_error
        || lastEventType == SessionEventType.tool_error
        || lastEventType == SessionEventType.abort;
  }
}

final class ScheduledExecutorAgentScheduler implements AgentScheduler {

  private final ScheduledExecutorService scheduledExecutorService;

  ScheduledExecutorAgentScheduler(ScheduledExecutorService scheduledExecutorService) {
    this.scheduledExecutorService = scheduledExecutorService;
  }

  @Override
  public ScheduledTask schedule(Duration delay, Runnable task) {
    long delayMillis = delay == null ? 0L : Math.max(0L, delay.toMillis());
    ScheduledFuture<?> future =
        scheduledExecutorService.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }
}

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

final class SingleModelRegistry implements ModelRegistry {

  private final ModelInfo modelInfo;

  SingleModelRegistry(ModelInfo modelInfo) {
    this.modelInfo = modelInfo;
  }

  @Override
  public void registerModel(ModelInfo modelInfo) {
    throw new UnsupportedOperationException("registerModel is not supported");
  }

  @Override
  public ModelInfo getModel(String provider, String model) {
    if (modelInfo == null) {
      return null;
    }
    if (!modelInfo.getProvider().equals(provider)) {
      return null;
    }
    return modelInfo.getName().equals(model) ? modelInfo : null;
  }
}

final class SingleProviderRegistry implements ProviderRegistry {

  private final String providerName;
  private final ProviderInfo providerInfo;

  SingleProviderRegistry(String providerName, ProviderInfo providerInfo) {
    this.providerName = providerName;
    this.providerInfo = providerInfo;
  }

  @Override
  public void registerProvider(String provider, ProviderInfo providerInfo) {
    throw new UnsupportedOperationException("registerProvider is not supported");
  }

  @Override
  public ProviderInfo getProviderInfo(String provider) {
    return providerName != null && providerName.equals(provider) ? providerInfo : null;
  }
}
