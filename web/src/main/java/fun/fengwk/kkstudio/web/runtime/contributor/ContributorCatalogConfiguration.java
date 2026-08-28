package fun.fengwk.kkstudio.web.runtime.contributor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 组合根收集 Spring 容器中的 {@link HarnessContributor} bean 与受信任 JAR 中的快照，冻结为单一不可变 {@link
 * HarnessCatalog}。
 */
@Configuration(proxyBeanMethods = false)
public class ContributorCatalogConfiguration {

  @Bean(destroyMethod = "close")
  public TrustedJarContributorLoader trustedJarContributorLoader(Environment environment) {
    return TrustedJarContributorLoader.fromConfiguredDirectory(
        environment.getProperty(TrustedJarContributorLoader.DIRECTORY_PROPERTY));
  }

  @Bean
  public HarnessCatalog harnessCatalog(
      ObjectProvider<HarnessContributor> contributors,
      TrustedJarContributorLoader trustedJarContributorLoader) {
    List<HarnessContributor> allContributors =
        new ArrayList<>(contributors.orderedStream().toList());
    allContributors.addAll(trustedJarContributorLoader.contributors());
    return HarnessCatalog.from(allContributors);
  }
}
