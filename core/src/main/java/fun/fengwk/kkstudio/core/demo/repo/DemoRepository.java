package fun.fengwk.kkstudio.core.demo.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.demo.service.model.Demo;

/**
 * @author fengwk
 */
public interface DemoRepository {

    long generateId();

    boolean add(Demo demo);

    boolean removeById(long id);

    Page<Demo> page(PageQuery pageQuery);

}
