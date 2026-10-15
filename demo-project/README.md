# demo-project —— Slow SQL Locator 插件测试工程

一个极简的 Java / MyBatis 示例工程，**不需要真正编译或连数据库**。
它的唯一用途是给插件提供可扫描的代码上下文（Mapper XML + 注解 Mapper），用来验证插件能否把一条慢 SQL 定位到具体文件和行号。

## 目录结构

```
demo-project/
├── 测试SQL.sql                                  ← 15 条测试 SQL + 实测预期结果（核心）
├── .gitignore                                   ← 忽略插件生成的 .sql-locator-index 与 .sql-locator-history
├── app/
│   └── src/main/
│       ├── java/com/demo/app/DemoApp.java        ← 极简示例程序（模拟慢 SQL 调用现场）
│       └── resources/mapper/LogMapper.xml        ← 两表 JOIN（t_log + t_user）→ 用于"表重合"用例
├── order-service/
│   └── src/main/
│       ├── java/com/demo/order/OrderMapper.java  ← 注解式 Mapper（@Select/@Update/@Insert/@Delete）
│       └── resources/mapper/OrderMapper.xml      ← 多表 JOIN / foreach IN 列表 / UPDATE
└── user-service/
    └── src/main/resources/mapper/UserMapper.xml  ← 增删改查 + <if> 动态标签
```

共 3 个 Mapper XML + 1 个注解 Mapper，覆盖 14 条源码 SQL；测试清单含 15 条查询 SQL。

## 测试方式一：IDEA 插件（推荐）

1. 用 IDEA 打开 **`demo-project` 目录本身**作为项目根（File → Open → 选中 demo-project）
2. 按 `Ctrl+Alt+L`，或右键编辑器 → **"Locate Slow SQL"**
3. 打开 `测试SQL.sql`，复制任意一条 SQL 粘贴到弹窗
4. 对照同一条 SQL 下方的 `-- 预期` 注释比较结果

首次使用时插件会自动扫描整个工程建立索引（弹窗会显示"索引规模：14 条"）。

## 测试方式二：命令行（可选，用于交叉验证）

```bash
cd demo-project
# 建索引
java -cp "../build/verify-zip/slow-sql-locator/lib/sql-locator-core.jar" com.loc.CliMain --build .
# 查询（把 SQL 作为参数，或用管道从标准输入喂入）
java -cp "../build/verify-zip/slow-sql-locator/lib/sql-locator-core.jar" com.loc.CliMain "SELECT u.id, u.name, u.email FROM t_user u WHERE u.id = :1"
```

## 用例覆盖情况

| 用例 | 场景 | 命中级 | 结果 |
|---|---|---|---|
| T1 | XML SELECT 按主键查 | L1 指纹 | ✅ |
| T2 | XML UPDATE | L1 指纹 | ✅ |
| T3 | XML INSERT | L1 指纹 | ✅ |
| T4 | XML DELETE | L1 指纹 | ✅ |
| T5 | XML 含 `<if>` 动态标签 | L1 指纹 | ✅ |
| T6 | 两表 JOIN | L1 指纹 | ✅ |
| T7 | 同表但 WHERE 条件列不同 | 未命中 | ✅ |
| T8 | JOIN 表/关联列与源码不一致 | 未命中 | ✅ |
| T9 | Java `@Select` | L1 指纹 | ✅ |
| T10 | `<foreach>` IN 列表 vs 日志里的 `IN (?,?,?)` | L2 骨架 | ✅ |
| T11 | Java `@Update` | L1 指纹 | ✅ |
| T12 | 工程里没有的表 | 兜底 grep | ✅ |
| T13 | JOIN 表/关联列与源码不一致 | 未命中 | ✅ |
| T14 | 别名等价（表名前缀 vs 别名） | L2 骨架 | ✅ |
| T15 | JOIN 关联列不一致 | 未命中 | ✅ |

## 匹配规则说明

v1.4 起的匹配策略：

1. **L1 指纹命中**：归一化后文本完全一致。
2. **L2 骨架一致**：表集合（忽略别名）完全一致，且**语句类型、WHERE 条件列集合、JOIN 表与 ON 关联列**均一致。
3. **L3 表重合**：主表一致、表集合部分重叠（>= 0.5），且类型/条件/JOIN 一致。
4. **L4 弱相关**：主表一致但重合度低（>= 0.2），且类型/条件/JOIN 一致。

**硬性过滤**：类型不同、主表不同、WHERE 条件列不同、JOIN 表或 ON 关联列不同，均直接丢弃。
