package fun.fengwk.kkstudio.core.demo.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.share.model.DemoCreateDTO;
import fun.fengwk.kkstudio.share.model.DemoDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class DemoServiceTest {

    @Autowired
    private DemoService demoService;

    @Test
    public void test() {
        DemoCreateDTO createDTO = new DemoCreateDTO();
        createDTO.setName("test");
        DemoDTO demoDTO = demoService.createDemo(createDTO);
        assertNotNull(demoDTO);
        assertEquals(createDTO.getName(), demoDTO.getName());

        PageQuery pageQuery = new PageQuery(1, 10);
        Page<DemoDTO> page = demoService.pageDemo(pageQuery);
        assertEquals(page.getTotalCount(), 1L);
        DemoDTO resultDTO = page.getResults().get(0);
        assertEquals(demoDTO.getId(), resultDTO.getId());
        assertEquals(demoDTO.getName(), resultDTO.getName());
        // 允许100ms以内的误差（数据库时间戳精度问题）
        assertTrue(withinTolerance(demoDTO.getCreateTime(), resultDTO.getCreateTime(), 100));
        assertTrue(withinTolerance(demoDTO.getUpdateTime(), resultDTO.getUpdateTime(), 100));

        demoService.removeDemo(demoDTO.getId());
        page = demoService.pageDemo(pageQuery);
        assertEquals(page.getTotalCount(), 0L);
    }

    private boolean withinTolerance(LocalDateTime expected, LocalDateTime actual, long toleranceMillis) {
        return Math.abs(ChronoUnit.MILLIS.between(expected, actual)) <= toleranceMillis;
    }

}
