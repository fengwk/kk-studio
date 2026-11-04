package fun.fengwk.kkstudio.platform.vfs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.VfsGateway;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader;
import fun.fengwk.kkstudio.platform.harness.read.PlatformSkillContentReader;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

/** 装配 Platform VFS：文件工具在 {@code vfs:} 路径上的唯一执行实现。 */
@Configuration(proxyBeanMethods = false)
public class VfsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ThreadMembershipValidator vfsThreadMembershipValidator(
      ObjectProvider<HarnessRuntime> harnessRuntime) {
    return new HarnessThreadMembershipValidator(harnessRuntime);
  }

  @Bean
  @ConditionalOnMissingBean
  public VfsGateway vfsGateway(
      PlatformSkillContentReader skillReader,
      PlatformResourceContentReader resourceReader,
      SessionFileStore sessionFileStore,
      StorageBlobManager storageBlobManager,
      ThreadMembershipValidator threadMembershipValidator) {
    return new PlatformVfsGateway(
        skillReader,
        resourceReader,
        sessionFileStore,
        storageBlobManager,
        threadMembershipValidator);
  }
}
