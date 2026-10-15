package com.test;

import org.apache.ibatis.annotations.*;

public interface OrderMapper {

    @Select("SELECT o.id, o.order_no, o.amount FROM t_order o WHERE o.user_id = #{userId}")
    Object selectOrderByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM t_order WHERE id = #{id}")
    int deleteOrder(@Param("id") Long id);
}
