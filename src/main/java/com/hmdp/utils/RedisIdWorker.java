package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //开始时间
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //序列号位数
    private static final int COUNT_BITS = 32;
    //注入redis
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public  long nextId(String keyPrefix) {
        // 前缀区分不同业务
        // id生成策略，准备时间戳和序列号
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 自增长，用到了redis的，现在上面注入
        //有问题，单个key自增长，有上限。序列号只有32bit，同一个key一直增就存不下了。
        // 拼个时间戳,每天下的单用相同的key，还有个统计效果。
        //stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":");
        // 2.1获取当前日期，天。
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:mm:dd"));
        // 2.2自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 3.拼接并返回
        //简单拼接是string，用位运算。时间戳左移序列号的位数。这里是32;|运算填充，count是什么就会保留什么
        return  timestamp << COUNT_BITS | count;

    }

//    public static void main(String[] args) {
//        // 快捷键生成main函数，psvm
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("second=" + second);
//    }
}