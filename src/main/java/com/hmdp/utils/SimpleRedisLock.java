package com.hmdp.utils;

import lombok.Builder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

// import java.util.UUID;
import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    //构造函数赋值，alter insert
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPTS;
    static {
        UNLOCK_SCRIPTS = new DefaultRedisScript<>();
        UNLOCK_SCRIPTS.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPTS.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(success);//自动拆箱，避免空指针
    }

    @Override
    public void unlock() {
        // 基于Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPTS,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }

//    @Override
//    public void unlock() {
//        // 获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if(threadId.equals(id)) {
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
