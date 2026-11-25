package fun.fengwk.kkstudio.web;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;

/** Runs after Flyway and before the production web server starts accepting requests. */
@Component
@Profile("prod")
@DependsOnDatabaseInitialization
class ProductionAdminBootstrap implements InitializingBean {
  private final StudioAccountService accounts;

  ProductionAdminBootstrap(StudioAccountService accounts) {
    this.accounts = accounts;
  }

  @Bean
  static BeanFactoryPostProcessor rejectDisposableProfiles(Environment environment) {
    return factory -> {
      if (environment.matchesProfiles("dev", "e2e", "canvas-test")) {
        throw new IllegalStateException(
            "The prod profile cannot be combined with dev, e2e or canvas-test");
      }
    };
  }

  @Override
  public void afterPropertiesSet() {
    accounts.bootstrapAdmin(System.getenv("KK_STUDIO_BOOTSTRAP_ADMIN_PASSWORD"));
  }
}
