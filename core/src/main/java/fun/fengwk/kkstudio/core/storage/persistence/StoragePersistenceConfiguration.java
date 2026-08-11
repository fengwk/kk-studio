package fun.fengwk.kkstudio.core.storage.persistence;

import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * 存储持久化的 MyBatis 配置：注册 {@code uuid -> UUID} 类型处理器。
 *
 * <p>无条件装配（不依赖 S3 开关）：mapper 接口在启动时即被解析，storage 表全部使用 uuid 列， 缺处理器会让任何部署（含未启用 S3 的部署）在 mapper
 * 解析阶段失败。
 *
 * @author fengwk
 */
@Configuration
public class StoragePersistenceConfiguration {

  @Bean
  public ConfigurationCustomizer storageUuidTypeHandlerRegistration() {
    return configuration ->
        configuration.getTypeHandlerRegistry().register(UUID.class, new StorageUuidTypeHandler());
  }
}
