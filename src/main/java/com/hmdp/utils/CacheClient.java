package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

// 加component，将来由spring维护
@Component
// 工具类，日志记录
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //四个方法，两个set，两个get
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 逻辑国企
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // unit.toSeconds()是为了确保计时单位是秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        // value值都封装到了redisdata中,所以写入的也是redisdata
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }

    // 缓存穿透
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、从Redis中查询店铺数据
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回店铺数据
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空
        if (json != null) {
            // 返回错误
            return null;
        }

        // 4.不存在，根据id查数据库,dbfallback
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);

        return r;
    }

    //线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、从Redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 视频中讲的本来的逻辑是有问题的。一直显示店铺不存在
        if (StrUtil.isBlank(json)) {
            // 3.存在直接返回
            return null;
        }
        // 4.命中，先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1未过期，返回店铺信息
            return r;
        }
        // 5.2过期，重建缓存
        // 6.重建缓存
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2判断获取锁成功否
        if (isLock){
            // 6.3成功，开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e){
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4返回过期的商铺信息
        return r;
    }

    // 两个锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}

