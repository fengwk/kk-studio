package fun.fengwk.kkstudio.core.demo.repo.impl.model;

import fun.fengwk.convention4j.springboot.starter.persistence.ConventionDO;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class DemoDO extends ConventionDO<Long> {

    private String name;

}
