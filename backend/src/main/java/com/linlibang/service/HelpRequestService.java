package com.linlibang.service;

import com.linlibang.dto.HelpRequestDTO;
import com.linlibang.dto.Result;

/**
 * 求助服务接口
 */
public interface HelpRequestService {

    /**
     * 发布求助
     *
     * @param dto     求助表单
     * @return 发布结果
     */
    Result publishHelp(HelpRequestDTO dto);

    /**
     * 查询求助列表（首页分页 / 关键词搜索）
     *
     * @param page       页码
     * @param size       每页条数
     * @param categoryId 分类ID（可选）
     * @param keyword    搜索关键词（可选）
     * @return 求助列表
     */
    Result getHelpList(Integer page, Integer size, Long categoryId, String keyword);

    /**
     * 查询求助详情
     *
     * @param helpId 求助ID
     * @return 求助详情
     */
    Result getHelpById(Long helpId);

    /**
     * 取消求助（仅发布者可操作；操作者取自当前登录态）
     *
     * @param helpId 求助ID
     * @return 取消结果
     */
    Result cancelHelp(Long helpId);

    /**
     * 查询我的求助列表（用户取自当前登录态）
     *
     * @param page 页码
     * @param size 每页条数
     * @return 求助列表
     */
    Result getMyHelp(Integer page, Integer size);

    /**
     * 搜索求助
     *
     * @param keyword    关键词
     * @param categoryId 分类ID（可选）
     * @param page       页码
     * @param size       每页条数
     * @return 搜索结果
     */
    Result searchHelp(String keyword, Long categoryId, Integer page, Integer size);

    /**
     * 管理员修改任意用户求助的状态（含下架），需 help:manage 权限
     *
     * @param helpId 求助ID
     * @param status 目标状态：1招募中 2进行中 3已完成 4已取消(下架)
     * @return 操作结果
     */
    Result adminUpdateStatus(Long helpId, Integer status);

    /**
     * 失效某条求助的缓存（详情 L1+L2 与分页列表），管理端改动数据后调用
     *
     * @param helpId 求助ID
     */
    void evictHelpCache(Long helpId);
}
