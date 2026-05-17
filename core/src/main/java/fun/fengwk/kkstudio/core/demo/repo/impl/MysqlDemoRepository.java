package fun.fengwk.kkstudio.core.demo.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.idgen.NamespaceIdGenerator;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.kkstudio.core.demo.repo.DemoRepository;
import fun.fengwk.kkstudio.core.demo.repo.impl.mapper.DemoMapper;
import fun.fengwk.kkstudio.core.demo.repo.impl.model.DemoDO;
import fun.fengwk.kkstudio.core.demo.service.model.Demo;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlDemoRepository implements DemoRepository {

    private final NamespaceIdGenerator<Long> idGenerator;
    private final DemoMapper demoMapper;

    @Override
    public long generateId() {
        return idGenerator.next(getClass());
    }

    @Override
    public boolean add(Demo demo) {
        if (demo == null) {
            return false;
        }
        return demoMapper.insertSelective(convert(demo)) > 0;
    }

    @Override
    public boolean removeById(long id) {
        return demoMapper.deleteById(id) > 0;
    }

    @Override
    public Page<Demo> page(PageQuery pageQuery) {
        long offset = Pages.queryOffset(pageQuery);
        int limit = Pages.queryLimit(pageQuery);
        List<DemoDO> result = demoMapper.pageAll(offset, limit);
        long totalCount = demoMapper.countAll();
        return Pages.page(pageQuery, result, totalCount)
            .map(this::convert);
    }

    private DemoDO convert(Demo demo) {
        if (demo == null) {
            return null;
        }
        DemoDO demoDO = new DemoDO();
        demoDO.setName(demo.getName());
        demoDO.setCreateTime(demo.getCreateTime());
        demoDO.setModifiedTime(demo.getUpdateTime());
        demoDO.setId(demo.getId());
        return demoDO;
    }

    private Demo convert(DemoDO demoDO) {
        if (demoDO == null) {
            return null;
        }
        Demo demo = new Demo();
        demo.setId(demoDO.getId());
        demo.setName(demoDO.getName());
        demo.setCreateTime(demoDO.getCreateTime());
        demo.setUpdateTime(demoDO.getModifiedTime());
        return demo;
    }

}
