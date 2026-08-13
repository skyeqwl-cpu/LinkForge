package com.linkforge.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.entity.ShortLink;
import com.linkforge.mapper.ShortLinkMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ShortLinkSyncTask {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    private static final String CLICK_COUNT_KEY_PREFIX = "short_link:click:";

    /**
     * 每 1 分钟执行一次
     * 将 Redis 中的点击次数同步到 MySQL
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void syncClickCount() {

        System.out.println("====================================");
        System.out.println("开始执行 Redis → MySQL 点击次数同步");
        System.out.println("====================================");

        // 1. 查询所有点击统计 Key
        Set<String> keys = stringRedisTemplate.keys(
                CLICK_COUNT_KEY_PREFIX + "*"
        );

        if (keys == null || keys.isEmpty()) {
            System.out.println("Redis 中没有需要同步的点击数据");
            return;
        }

        System.out.println("发现 " + keys.size() + " 个点击统计 Key");

        // 2. 遍历 Redis Key
        for (String key : keys) {

            try {

                // short_link:click:1
                // 截取出 shortCode
                String shortCode =
                        key.substring(CLICK_COUNT_KEY_PREFIX.length());

                // 3. 获取 Redis 点击次数
                String clickValue =
                        stringRedisTemplate.opsForValue().get(key);

                if (clickValue == null) {
                    continue;
                }

                long clickCount = Long.parseLong(clickValue);

                System.out.println(
                        "同步短链接：" + shortCode
                                + "，点击次数：" + clickCount
                );

                // 4. 查询 MySQL
                LambdaQueryWrapper<ShortLink> wrapper =
                        new LambdaQueryWrapper<>();

                wrapper.eq(
                        ShortLink::getShortCode,
                        shortCode
                );

                ShortLink shortLink =
                        shortLinkMapper.selectOne(wrapper);

                // 5. MySQL 中不存在
                if (shortLink == null) {

                    System.out.println(
                            "MySQL 中不存在短链接："
                                    + shortCode
                    );

                    continue;
                }

                // 6. 更新 MySQL
                shortLink.setClickCount(clickCount);

                shortLinkMapper.updateById(shortLink);

                System.out.println(
                        "同步成功："
                                + shortCode
                                + " → "
                                + clickCount
                );

            } catch (Exception e) {

                System.err.println(
                        "同步失败，Key：" + key
                );

                e.printStackTrace();
            }
        }

        System.out.println("====================================");
        System.out.println("Redis → MySQL 同步任务执行完成");
        System.out.println("====================================");
    }
}