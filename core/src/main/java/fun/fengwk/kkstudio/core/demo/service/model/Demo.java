package fun.fengwk.kkstudio.core.demo.service.model;

import fun.fengwk.kkstudio.share.model.DemoCreateDTO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class Demo {

    private long id;
    private String name;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static Demo create(long id, DemoCreateDTO createDTO) {
        Demo demo = new Demo();
        demo.setId(id);
        demo.setName(createDTO.getName());
        LocalDateTime now = LocalDateTime.now();
        demo.setCreateTime(now);
        demo.setUpdateTime(now);
        return demo;
    }

}
