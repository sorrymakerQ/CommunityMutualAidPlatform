package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.linlibang.dto.HelpRequestDTO;
import com.linlibang.dto.Result;
import com.linlibang.service.HelpRequestService;
import com.linlibang.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.concurrent.TimeUnit;

/**
 * 求助控制器
 * 处理求助发布、搜索、查看等请求
 */
@RestController
@RequestMapping("/help")
public class HelpRequestController {

    @Resource
    private HelpRequestService helpRequestService;

    @Resource
    private RedisUtils redisUtils;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发布求助 — 需要 help:publish 权限
     * 幂等：前端写请求带 X-Request-Id（双击/网络重试只成功一次）
     */
    @SaCheckPermission("help:publish")
    @PostMapping("/publish")
    public Result publishHelp(@Valid @RequestBody HelpRequestDTO dto,
                              @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long userId = StpUtil.getLoginIdAsLong();
        // 幂等键（Redis SETNX，30 分钟窗口）
        if (requestId != null && !requestId.trim().isEmpty()) {
            if (stringRedisTemplate.opsForValue().setIfAbsent("idem:publish:" + userId + ":" + requestId.trim(), "1", 30, TimeUnit.MINUTES)) {
                return Result.fail("请勿重复提交");
            }
        }
        return helpRequestService.publishHelp(dto);
    }

    /**
     * 查询求助列表（首页分页 / 关键词搜索）
     *
     * @param page       页码，默认1
     * @param size       每页条数，默认10
     * @param categoryId 分类ID（可选）
     * @param keyword    关键词（可选）
     */
    @GetMapping("/list")
    public Result getHelpList(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);  // 限制最大每页条数
        return helpRequestService.getHelpList(page, size, categoryId, keyword);
    }

    /**
     * 查询求助详情
     *
     * @param id 求助ID
     */
    @GetMapping("/{id}")
    public Result getHelpById(@PathVariable Long id) {
        return helpRequestService.getHelpById(id);
    }

    /**
     * 搜索求助
     *
     * @param keyword    搜索关键词
     * @param categoryId 分类ID（可选）
     * @param page       页码
     * @param size       每页条数
     */
    @GetMapping("/search")
    public Result searchHelp(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);  // 限制最大每页条数
        return helpRequestService.searchHelp(keyword, categoryId, page, size);
    }

    /**
     * 取消求助 — 需登录
     *
     * @param id 求助ID
     */
    @SaCheckLogin
    @PutMapping("/{id}/cancel")
    public Result cancelHelp(@PathVariable Long id) {
        return helpRequestService.cancelHelp(id);
    }

    /**
     * 我的求助列表 — 需登录
     *
     * @param page 页码
     * @param size 每页条数
     */
    @SaCheckLogin
    @GetMapping("/my")
    public Result getMyHelp(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);  // 限制最大每页条数
        return helpRequestService.getMyHelp(page, size);
    }
}
