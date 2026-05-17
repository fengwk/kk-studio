package fun.fengwk.kkstudio.core.demo.service.converter;

import fun.fengwk.kkstudio.core.demo.service.model.Demo;
import fun.fengwk.kkstudio.share.model.DemoDTO;
import org.springframework.stereotype.Component;

/**
 * @author fengwk
 */
@Component
public class DemoConverter {

    public DemoDTO convert(Demo demo) {
        if (demo == null) {
            return null;
        }
        DemoDTO demoDTO = new DemoDTO();
        demoDTO.setId(demo.getId());
        demoDTO.setName(demo.getName());
        demoDTO.setCreateTime(demo.getCreateTime());
        demoDTO.setUpdateTime(demo.getUpdateTime());
        return demoDTO;
    }

}
