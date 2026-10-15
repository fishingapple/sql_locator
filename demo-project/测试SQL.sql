-- ============================================================
--  Slow SQL Locator 插件 —— 测试 SQL 清单（预期结果均为实测输出）
-- ------------------------------------------------------------
--  使用方法：
--    1. 用 IDEA 打开 demo-project 目录作为项目根
--    2. 右键编辑器 或 按 Ctrl+Alt+L，选 "Locate Slow SQL"
--    3. 把下面任意一条 SQL 粘贴进弹窗，对照"-- 预期"注释
--  说明：SQL 里的 :1 / :2 是慢 SQL 日志里的占位符，插件会自动归一化，
--        用 ? 或 具体值 也一样能命中。
-- ============================================================


-- ------------------------------------------------------------
-- T1 指纹命中（XML SELECT，最常用场景）
-- ------------------------------------------------------------
SELECT u.id, u.name, u.email FROM t_user u WHERE u.id = :1
-- 预期（仅命中同类型同条件的 SELECT；不再出现 update/insert/delete 或 t_log 等无关表）：
--   [★指纹命中] com.demo.user.UserMapper.selectUserById   @ user-service\src\main\resources\mapper\UserMapper.xml:6


-- ------------------------------------------------------------
-- T2 指纹命中（XML UPDATE）
-- ------------------------------------------------------------
UPDATE t_user SET status = :1, update_time = NOW() WHERE id = :2
-- 预期（仅命中同类型同条件的 UPDATE；不再出现 insert/delete/select）：
--   [★指纹命中] com.demo.user.UserMapper.updateUserStatus  @ user-service\src\main\resources\mapper\UserMapper.xml:23


-- ------------------------------------------------------------
-- T3 指纹命中（XML INSERT）
-- ------------------------------------------------------------
INSERT INTO t_user (id, name, email, status) VALUES (:1, :2, :3, :4)
-- 预期：
--   [★指纹命中] com.demo.user.UserMapper.insertUser        @ user-service\src\main\resources\mapper\UserMapper.xml:30


-- ------------------------------------------------------------
-- T4 指纹命中（XML DELETE）
-- ------------------------------------------------------------
DELETE FROM t_user WHERE id = :1
-- 预期：
--   [★指纹命中] com.demo.user.UserMapper.deleteUserById    @ user-service\src\main\resources\mapper\UserMapper.xml:36


-- ------------------------------------------------------------
-- T5 动态标签剥离（XML 里带 <if>，粘贴的 SQL 不带）
-- ------------------------------------------------------------
SELECT u.id, u.name, u.email FROM t_user u WHERE u.status = 1 AND u.name LIKE CONCAT('%', :1, '%')
-- 预期（能命中说明 <if> 动态标签已被正确剥离后归一化）：
--   [★指纹命中] com.demo.user.UserMapper.selectActiveUsers @ user-service\src\main\resources\mapper\UserMapper.xml:13


-- ------------------------------------------------------------
-- T6 指纹命中（多表 JOIN）
-- ------------------------------------------------------------
SELECT o.id, o.order_no, u.name FROM t_order o JOIN t_user u ON o.user_id = u.id WHERE o.id = :1
-- 预期：
--   [★指纹命中] com.demo.order.OrderMapper.selectOrderWithUser @ order-service\src\main\resources\mapper\OrderMapper.xml:6


-- ------------------------------------------------------------
-- T7 条件不匹配被过滤（同表 t_order，但源码只有 id 条件，查询是 status）
-- ------------------------------------------------------------
SELECT o.id, o.status FROM t_order o WHERE o.status = :1
-- 预期：
--   未命中索引（条件列不一致）。
--   grep 兜底: grep -rniE '\bT_ORDER\b' --include=*.xml --include=*.java src/


-- ------------------------------------------------------------
-- T8 JOIN 不匹配被过滤（源码 JOIN 的是 t_user，查询是 t_dept）
-- ------------------------------------------------------------
SELECT l.id, l.action FROM t_log l JOIN t_dept d ON l.dept_id = d.id WHERE l.id = :1
-- 预期：
--   未命中索引（JOIN 表/关联列不一致）。
--   grep 兜底: grep -rniE '\bT_LOG\b|\bT_DEPT\b' --include=*.xml --include=*.java src/


-- ------------------------------------------------------------
-- T9 指纹命中（Java 注解 @Select，不是 XML）
-- ------------------------------------------------------------
SELECT o.id, o.order_no, o.user_id FROM t_order o WHERE o.order_no = :1
-- 预期：
--   [★指纹命中] order-service\src\main\java\com\demo\order\OrderMapper.java:@Select @ ...OrderMapper.java:15


-- ------------------------------------------------------------
-- T10 骨架一致（XML 里是 <foreach> 生成的 IN 列表，日志里是展开的 IN (?,?,?)）
-- ------------------------------------------------------------
SELECT i.id, i.order_id, i.sku FROM t_order_item i WHERE i.id IN (:1, :2, :3)
-- 预期（IN 列表被压缩归一化 → 靠骨架命中）：
--   [☆骨架一致] com.demo.order.OrderMapper.selectItemsByIds @ order-service\src\main\resources\mapper\OrderMapper.xml:21


-- ------------------------------------------------------------
-- T11 指纹命中（Java 注解 @Update）
-- ------------------------------------------------------------
UPDATE t_order SET status = :1 WHERE order_no = :2
-- 预期：
--   [★指纹命中] order-service\src\main\java\com\demo\order\OrderMapper.java:@Update @ ...OrderMapper.java:19


-- ------------------------------------------------------------
-- T12 未命中（工程里没有的表 → 走 grep 兜底）
-- ------------------------------------------------------------
SELECT x.id FROM t_unknown_table x WHERE x.id = :1
-- 预期：
--   未命中索引。
--   grep 兜底: grep -rniE '\bT_UNKNOWN_TABLE\b' --include=*.xml --include=*.java src/


-- ------------------------------------------------------------
-- T13 JOIN 不匹配被过滤（4 表查询，工程里没有这些 JOIN 关系）
-- ------------------------------------------------------------
SELECT a.id FROM t_user a JOIN t_dept d ON a.dept_id = d.id JOIN t_role r ON d.role_id = r.id JOIN t_perm p ON r.perm_id = p.id WHERE a.id = :1
-- 预期：
--   未命中索引（JOIN 表/关联列不一致）。
--   grep 兜底: grep -rniE '\bT_USER\b|\bT_DEPT\b|\bT_ROLE\b|\bT_PERM\b' --include=*.xml --include=*.java src/


-- ------------------------------------------------------------
-- T14 别名等价（查询用表名前缀，源码用别名）
-- ------------------------------------------------------------
SELECT id, name FROM t_user WHERE t_user.id = :1
-- 预期（WHERE 列 key 使用纯列名，T_USER.ID 与 U.ID 等价）：
--   [☆骨架一致] com.demo.user.UserMapper.selectUserById @ user-service\src\main\resources\mapper\UserMapper.xml:6


-- ------------------------------------------------------------
-- T15 JOIN 关联列不一致被过滤
-- ------------------------------------------------------------
SELECT o.id FROM t_order o JOIN t_user u ON o.order_no = u.name WHERE o.id = :1
-- 预期：
--   未命中索引（JOIN ON 等式列与源码不一致）。
--   grep 兜底: grep -rniE '\bT_ORDER\b|\bT_USER\b' --include=*.xml --include=*.java src/
