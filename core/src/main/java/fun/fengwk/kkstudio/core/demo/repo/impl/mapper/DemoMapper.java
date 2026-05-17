package fun.fengwk.kkstudio.core.demo.repo.impl.mapper;

import fun.fengwk.automapper.annotation.AutoMapper;
import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.demo.repo.impl.model.DemoDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author fengwk
 */
@AutoMapper
public interface DemoMapper extends BaseMapper {

    int insertSelective(DemoDO demoDO);

    int deleteById(long id);

    long countAll();

    List<DemoDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

}
