package fun.fengwk.kkstudio.core.demo.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.share.model.DemoCreateDTO;
import fun.fengwk.kkstudio.share.model.DemoDTO;

/**
 * @author fengwk
 */
public interface DemoService {

    DemoDTO createDemo(DemoCreateDTO createDTO);

    void removeDemo(long id);

    Page<DemoDTO> pageDemo(PageQuery pageQuery);

}