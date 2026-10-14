package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/** Git 准备完成后才开启短写事务；独立 Bean 保证调用经过 Spring 事务代理。 */
@Component
public class SkillCatalogWrites {
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T execute(Supplier<T> write) {
    return write.get();
  }
}
