package com.linkforge.consumer;

import com.linkforge.event.ShortLinkClickEvent;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "short-link-click-topic",
        consumerGroup = "short-link-click-consumer-group"
)
public class ShortLinkClickConsumer implements RocketMQListener<ShortLinkClickEvent> {

    @Override
    public void onMessage(ShortLinkClickEvent event) {
        System.out.println("====== MQ 收到点击事件 ======");
        System.out.println("短码: " + event.getShortCode());
        System.out.println("时间戳: " + event.getTimestamp());

        // TODO: 此处后续可扩展写入数据库日志表、计算 PV/UV、地区解析等耗时业务
    }
}