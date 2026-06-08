package com.hmdp;

import cn.hutool.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;

import javax.annotation.Priority;
import javax.annotation.Resource;
import java.security.PrivateKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    // 写数据，测试缓存击穿
    @Test
    void testSaveShop() throws InterruptedException{
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 1000L, TimeUnit.DAYS);
    }

    // 注入
    @Resource
    private RedisIdWorker redisIdWorker;
    // 线程池用来测试
    private ExecutorService es = Executors.newFixedThreadPool(500);

    // 测试全局唯一id
    @Test
    void testIdWorker() throws InterruptedException{
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i =0 ;i <100; i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        // for循环快捷就是fori
        //提交任务三百次
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end-begin));
    }


}
