package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandler;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionHandlerRegistry;

import java.util.List;

/** Wires registered generic Interaction handlers without making Runtime depend on Spring. */
@Configuration(proxyBeanMethods = false)
public class InteractionConfiguration {

  @Bean
  public InteractionHandlerRegistry interactionHandlerRegistry(List<InteractionHandler> handlers) {
    return new InteractionHandlerRegistry(handlers);
  }
}
