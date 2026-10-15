package com.demo.app;

/**
 * 极简示例程序：模拟一次线上慢 SQL 的调用现场。
 * <p>本工程不需要真正连数据库/编译运行，它存在的意义是给
 * Slow SQL Locator 插件提供可扫描的代码上下文（Mapper 接口 + Mapper XML），
 * 用来验证插件的定位能力。
 */
public class DemoApp {

    public static void main(String[] args) {
        OrderFlow flow = new OrderFlow();
        // 线上慢 SQL 的调用入口（对应 测试SQL.sql 中的 T6）
        Object order = flow.queryOrderWithUser(10086L);
        System.out.println("查询订单: " + order);
    }

    /** 模拟业务方法：调 Mapper 查询订单（对应 OrderMapper.selectOrderWithUser） */
    static class OrderFlow {
        Object queryOrderWithUser(Long orderId) {
            // 真实项目里这里是 orderMapper.selectOrderWithUser(orderId)
            return "order#" + orderId;
        }
    }
}
