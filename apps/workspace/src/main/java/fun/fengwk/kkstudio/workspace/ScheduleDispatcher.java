package fun.fengwk.kkstudio.workspace;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.work.WorkClaim;
import fun.fengwk.kkstudio.work.WorkDispatcherLifecycle;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
class ScheduleDispatcher {
  @Bean(destroyMethod = "shutdown")
  ExecutorService scheduleDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon().name("schedule-drain", 0).factory());
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService scheduleWorkerExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "shutdown")
  ScheduledExecutorService schedulePollExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("schedule-poll", 0).factory());
  }

  @Bean
  WorkDispatcherLifecycle scheduleLifecycle(
      WorkspaceSchedules schedules,
      ExecutorService scheduleDrainExecutor,
      ExecutorService scheduleWorkerExecutor,
      ScheduledExecutorService schedulePollExecutor) {
    var dispatcher =
        new BoundedWorkDispatcher<WorkClaim>(
            "schedule",
            2,
            Duration.ofSeconds(1),
            scheduleDrainExecutor,
            scheduleWorkerExecutor,
            schedulePollExecutor,
            () -> schedules.claim().orElse(null),
            schedules::auto,
            schedules::release);
    return new WorkDispatcherLifecycle(true, dispatcher);
  }
}
