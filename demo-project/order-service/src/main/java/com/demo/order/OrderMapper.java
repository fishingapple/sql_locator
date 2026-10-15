package com.demo.order;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 注解式 Mapper —— 插件同样会索引这里的 SQL（用例 T9 / T11）。
 * 注意：SQL 必须写在注解的同一个字符串里，否则扫描不到。
 */
public interface OrderMapper {

    // 用例 T9：指纹命中（注解 SELECT）
    @Select("SELECT o.id, o.order_no, o.user_id FROM t_order o WHERE o.order_no = #{orderNo}")
    Object selectByOrderNo(String orderNo);

    // 用例 T11：指纹命中（注解 UPDATE）
    @Update("UPDATE t_order SET status = #{status} WHERE order_no = #{orderNo}")
    int updateStatusByOrderNo(String status, String orderNo);

    @Insert("INSERT INTO t_order (order_no, user_id, amount) VALUES (#{orderNo}, #{userId}, #{amount})")
    int insertOrder(String orderNo, Long userId, java.math.BigDecimal amount);

    @Delete("DELETE FROM t_order WHERE order_no = #{orderNo}")
    int deleteByOrderNo(String orderNo);
}
