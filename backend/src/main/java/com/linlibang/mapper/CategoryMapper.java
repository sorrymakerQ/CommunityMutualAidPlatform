package com.linlibang.mapper;

import com.linlibang.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分类 Mapper 接口（MyBatis 注解方式）
 */
@Mapper
public interface CategoryMapper {

    /**
     * 根据ID查询分类
     */
    @Select("SELECT id,name,icon FROM tb_category WHERE id = #{id}")
    Category selectById(Long id);

    /**
     * 查询所有分类，按排序字段升序
     */
    @Select("SELECT id,name,icon FROM tb_category ORDER BY sort ASC")
    List<Category> selectAll();

    /**
     * 批量查询分类（用于避免 N+1 查询）
     */
    @Select("<script>" +
            "SELECT id,name,icon FROM tb_category WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Category> selectByIds(@Param("ids") List<Long> ids);
}
