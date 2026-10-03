import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 直连 broker 发一条消息，再用消费者收回来 —— 验证 RocketMQ 通道是否真的通 */
public class SendConsume {

    public static void main(String[] args) throws Exception {
        String namesrv = "127.0.0.1:9876";
        String topic = "linlibang-test-topic";
        String tag = "smoke";
        String body = "hello-rocketmq-" + System.currentTimeMillis();
        String consumerGroup = "test-consumer-" + System.currentTimeMillis();

        System.out.println("namesrv = " + namesrv + ", topic = " + topic);

        // ===== ① 生产者：发消息到 broker =====
        DefaultMQProducer producer = new DefaultMQProducer("test-producer-group");
        producer.setNamesrvAddr(namesrv);
        producer.setSendMsgTimeout(3000);
        producer.start();
        try {
            Message msg = new Message(topic, tag, body.getBytes("UTF-8"));
            msg.setKeys("smoke-" + System.currentTimeMillis());
            SendResult sr = producer.send(msg);
            System.out.println("发送结果 : status=" + sr.getSendStatus()
                    + " | msgId=" + sr.getMsgId()
                    + " | broker=" + sr.getMessageQueue().getBrokerName()
                    + " | topic=" + sr.getMessageQueue().getTopic()
                    + " | queueId=" + sr.getMessageQueue().getQueueId()
                    + " | offset=" + sr.getQueueOffset());
        } catch (Exception e) {
            System.out.println("发送失败 : " + e.getClass().getSimpleName() + " -> " + e.getMessage());
            System.out.println("（常见原因：broker 未开 autoCreateTopicEnable，或 NameServer 地址不对）");
        } finally {
            producer.shutdown();
        }

        // ===== ② 消费者：把刚才那条收回来 =====
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(namesrv);
        consumer.subscribe(topic, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        final CountDownLatch latch = new CountDownLatch(1);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                System.out.println("收到消息 : msgId=" + m.getMsgId()
                        + " | queueId=" + m.getQueueId()
                        + " | reconsumeTimes=" + m.getReconsumeTimes()
                        + " | body=" + new String(m.getBody()));
            }
            latch.countDown();
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        System.out.println("消费者已启动，等待消息...（最多 15 秒）");
        boolean got = latch.await(15, TimeUnit.SECONDS);
        System.out.println("是否收到: " + got);
        consumer.shutdown();
    }
}
