package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;

    // ctrl + o，实现
    // 开始业务流程
    @Override
    // @Transactional //两张表的操作，新增订单，减少库存，加上事务，出现问题能及时回滚 ,一人一单加锁，事务就不是在这里了
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券：先拿到优惠券的service，注入秒杀的service
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 未开始
            return Result.fail("秒杀尚未开始");
        }
        // 3.是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已结束
            return Result.fail("秒杀已结束");
        }
        // 4.库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 获取成功吗
        if (!isLock){
            // 失败，返回失败or重试。这里根据业务返回失败
            return Result.fail("一人一单");
        }

        try {
            // 加个intern，才能在值一样时候锁一样。不然即使userid一样，tostring之后也不一样
            // 获取代理对象（事务） 事务提交才释放锁
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放
            lock.unlock();
        }
    }
    @Transactional
    // 锁的位置，一人一单，加到id上
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 5.一人一单
        //Long userId = UserHolder.getUser().getId();
        // 5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2判断是否存在
        if (count > 0) {
            //购买过了
            return Result.fail("用户已购买过一次");
        }

        // 6.减库存,id相等减
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1订单id,id生成器生成，注入redisidworker
        long orderID = redisIdWorker.nextId("order");
        voucherOrder.setId(orderID);
        // 7.2用户id
        voucherOrder.setUserId(userId);
        // 7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7.返回订单id
        return Result.ok(orderID);
    }
}