package fun.fengwk.kkstudio.core.ai.runtime.command;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandlerRegistry;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;

import java.time.Clock;

/**
 * Wires framework-free runtime command/interaction coordinators as Core composition beans without
 * making harness-runtime depend on Spring.
 */
@Configuration(proxyBeanMethods = false)
public class HarnessCommandConfiguration {

  @Bean
  public ThreadCommandCoordinator threadCommandCoordinator(
      ThreadCommandTransactions transactions, RuntimeConfigSource configSource, Clock clock) {
    return new ThreadCommandCoordinator(transactions, configSource, clock);
  }

  @Bean
  public InteractionCoordinator interactionCoordinator(
      InteractionTransactions transactions,
      InteractionHandlerRegistry handlerRegistry,
      Clock clock) {
    return new InteractionCoordinator(transactions, handlerRegistry, clock);
  }
}
