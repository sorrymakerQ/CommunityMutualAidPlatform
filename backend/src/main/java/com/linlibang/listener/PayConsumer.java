package com.linlibang.listener;


import com.linlibang.config.RocketMQConfig;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Order;
import com.linlibang.entity.PayOrder;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.PayOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.bouncycastle.asn1.esf.OtherRevRefs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;


//支付消费者
@Slf4j
@Component
@RocketMQMessageListener(topic = RocketMQConfig.PAY_TOPIC, consumerGroup = RocketMQConfig.PAY_CONSUMER_GROUP)
public class PayConsumer implements RocketMQListener<PayOrder> {

    @Resource
    private OrderMapper orderMapper;
    @Autowired
    private PayOrderMapper payOrderMapper;
    @Autowired
    private HelpRequestMapper helpRequestMapper;

    @Override
    @Transactional
    public void onMessage(PayOrder order) {
        //判断order是不是为空
        if(order==null){
            log.error("order为空");
            return ;
        }
        //判断order的状态是否是已经支付
        if(order.getStatus()==1){
            log.info("消费成功");
            return ;
        }
        // 3. 更新订单状态
        order.setStatus(1);
        HelpRequest help = helpRequestMapper.selectById(order.getHelpId());
        if(help == null){
            return ;
        }
        Integer row = payOrderMapper.markPaid(order.getHelpId(), PayOrder.CHANNEL_ALIPAY);
        Integer row2 = helpRequestMapper.markPaid(order.getHelpId());
        if (row == 0 || row2 == 0)  {
            log.info("支付宝重复通知，已幂等跳过: helpId={}, payNo={}", order.getHelpId(), order.getPayNo());
            log.info("支付宝重复通知，已幂等跳过: helpId={}, payNo={}", order.getHelpId(), order.getPayNo());
            return ;
        }else{
            log.info("支付已经成功  已经成功更改状态: helpId={}", order.getHelpId());
            return ;
        }

    }



}
