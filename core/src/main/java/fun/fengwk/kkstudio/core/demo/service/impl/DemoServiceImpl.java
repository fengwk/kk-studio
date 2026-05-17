package fun.fengwk.kkstudio.core.demo.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.demo.repo.DemoRepository;
import fun.fengwk.kkstudio.core.demo.service.DemoService;
import fun.fengwk.kkstudio.core.demo.service.converter.DemoConverter;
import fun.fengwk.kkstudio.core.demo.service.model.Demo;
import fun.fengwk.kkstudio.share.constant.DemoErrorCodes;
import fun.fengwk.kkstudio.share.model.DemoCreateDTO;
import fun.fengwk.kkstudio.share.model.DemoDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@Slf4j
@AllArgsConstructor
@Service
public class DemoServiceImpl implements DemoService {

    private final DemoConverter demoConverter;
    private final DemoRepository demoRepository;

    @Override
    public DemoDTO createDemo(DemoCreateDTO createDTO) {
        Demo demo = Demo.create(demoRepository.generateId(), createDTO);
        if (!demoRepository.add(demo)) {
            log.error("Add demo failed, demo: {}", demo);
            throw DemoErrorCodes.CREATE_DEMO_FAILED.asThrowable();
        }
        return demoConverter.convert(demo);
    }

    @Override
    public void removeDemo(long id) {
        if (!demoRepository.removeById(id)) {
            log.error("Remove demo failed, id: {}", id);
            throw DemoErrorCodes.REMOVE_DEMO_FAILED.asThrowable();
        }
    }

    @Override
    public Page<DemoDTO> pageDemo(PageQuery pageQuery) {
        return demoRepository.page(pageQuery).map(demoConverter::convert);
    }

}
