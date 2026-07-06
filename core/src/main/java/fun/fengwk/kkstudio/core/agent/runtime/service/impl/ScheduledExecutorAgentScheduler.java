package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
