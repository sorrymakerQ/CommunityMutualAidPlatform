package com.linlibang.service.impl;

import com.linlibang.dto.Result;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.PayOrder;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.PayOrderMapper;
import com.linlibang.mapper.UserMapper;
import com.linlibang.service.PayService;
import com.linlibang.utils.RedisUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 支付服务实现（简单余额支付）
 *
 * 流程：余额 CAS 扣减 → 支付订单 0→1 → 求助 0→1（上首页）
 * 全部在同一事务：任一步失败整体回滚（余额不会扣了单没支付）
 * 幂等：已支付的求助/支付订单重复支付会被状态机 CAS 拦截
 */
@Service
public class PayServiceImpl implements PayService {

    /** 求助 GEO Key（支付成功后写入，进入附近搜索） */
    private static final String HELP_GEO_KEY = "help:location";
    /** 求助详情缓存前缀 */
    private static final String HELP_ITEM_KEY = "help:item:";
    /** 分页缓存前缀 */
    private static final String HELP_PAGE_KEY = "help:page:";

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private PayOrderMapper payOrderMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RedisUtils redisUtils;

    @Override
    @Transactional
    public Result pay(Long helpId, Long userId) {
        // 1. 校验求助：存在、是自己的、处于待支付
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在");
        }
        if (!help.getUserId().equals(userId)) {
            return Result.fail("只能支付自己发布的求助");
        }
        if (help.getStatus() != 0) {
            return Result.fail(help.getStatus() == 1 ? "该求助已支付，正在招募中" : "该求助当前状态不可支付");
        }

        // 2. 校验支付订单：存在且待支付
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null) {
            return Result.fail("支付订单不存在");
        }
        if (payOrder.getStatus() != 0) {
            return Result.fail(payOrder.getStatus() == 1 ? "该求助已支付，请勿重复支付" : "该支付订单已取消");
        }

        // 3. 余额扣减前兜底：金额为负时"扣减"实为加钱（刷钱漏洞），必须拦截。
        //    正常流程已在发布时拦截负数酬劳，这里再拦一层防历史/异常脏数据。
        if (payOrder.getAmount() == null || payOrder.getAmount().signum() < 0) {
            return Result.fail("支付金额非法，请联系客服");
        }

        // 4. 余额扣减（CAS 防超扣：余额不足影响行数为 0）
        int rows = userMapper.deductBalance(userId, payOrder.getAmount());
        if (rows == 0) {
            return Result.fail("余额不足，请先充值");
        }

        // 4. 支付订单 0→1（幂等：状态已被并发变更则抛异常回滚余额）
        if (payOrderMapper.updateStatusIf(helpId, 0, 1) == 0) {
            throw new IllegalStateException("支付订单状态已变更，请刷新后重试");
        }

        // 5. 求助 0→1（上首页：首页/搜索/附近只查 status=1）
        if (helpRequestMapper.updateStatusIf(helpId, 0, 1) == 0) {
            throw new IllegalStateException("求助状态已变更，请刷新后重试");
        }

        // 6. 事务提交后：写入 GEO（进入附近搜索）+ 清理缓存（详情/分页/用户余额）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisUtils.geoAdd(HELP_GEO_KEY, help.getLng(), help.getLat(), helpId.toString());
                redisUtils.delete(HELP_ITEM_KEY + helpId);
                clearPageCache();
                // 修复：支付扣减余额后必须失效用户缓存，否则 /user/me 长期返回旧余额
                redisUtils.delete("user:info:" + userId);
            }
        });

        return Result.ok("支付成功，求助已发布到首页", payOrder.getAmount());
    }

    @Override
    @Transactional
    public Result refundOrder(Long helpId, Long publisherId) {
        // 1. 支付订单校验（幂等：未支付无需退、已取消已退）
        PayOrder payOrder = payOrderMapper.selectByHelpId(helpId);
        if (payOrder == null) {
            return Result.fail("支付订单不存在");
        }
        if (payOrder.getStatus() != 1) {
            return Result.fail(payOrder.getStatus() == 2 ? "该订单已退款" : "该订单未支付，无需退款");
        }

        // 2. 校验求助归属（防止他人触发退款）
        HelpRequest help = helpRequestMapper.selectById(helpId);
        if (help == null) {
            return Result.fail("求助不存在");
        }
        if (!help.getUserId().equals(publisherId)) {
            return Result.fail("只能退款自己发布的求助");
        }

        // 3. 支付单 1→2（CAS 幂等；状态被并发变更则抛异常整体回滚）
        if (payOrderMapper.updateStatusIf(helpId, 1, 2) == 0) {
            throw new IllegalStateException("支付订单状态已变更，退款失败");
        }

        // 4. 计算应退金额：总额扣减"已完成订单已结算给接单者的酬劳"，
        //    避免部分完成后全额退款导致重复退款/资金缺口
        BigDecimal refundAmount = payOrder.getAmount();
        Long completedCount = orderMapper.countCompletedByHelpId(helpId);
        if (completedCount != null && completedCount > 0 && help.getReward() != null) {
            refundAmount = refundAmount.subtract(
                    help.getReward().multiply(BigDecimal.valueOf(completedCount)));
        }
        // 5. 余额原路退回（BigDecimal 原子加回，与上面同事务：任一步失败全套回滚）
        if (refundAmount.signum() > 0) {
            userMapper.addBalance(publisherId, refundAmount);
        }
        // 6. 退款变更了余额：提交后失效用户缓存，否则 /user/me 长期返回旧余额
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisUtils.delete("user:info:" + publisherId);
            }
        });

        return Result.ok("退款成功，已退回余额", refundAmount);
    }

    /** 清除分页列表缓存（数据变更时保持 Redis 与 DB 一致） */
    private void clearPageCache() {
        Set<String> keys = redisUtils.scanKeys(HELP_PAGE_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            redisUtils.delete(keys);
        }
    }
}
