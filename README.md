# Slow SQL Locator - 慢SQL定位工具

一个用于将慢SQL定位到代码位置（Mapper XML或注解）的工具，支持IntelliJ IDEA插件和命令行两种使用方式。

## 项目结构

```
SqlPoint/
├── core/                    # 核心模块
│   └── src/main/java/com/loc/
│       ├── SqlNormalizer.java      # SQL归一化
│       ├── Fingerprint.java        # SQL指纹、骨架、WHERE列、JOIN条件提取
│       ├── IndexBuilder.java       # 索引构建（扫描XML和Java文件）
│       ├── SqlLocator.java         # SQL搜索定位
│       ├── LocatorEngine.java      # 定位引擎
│       └── SqlLocation.java        # 定位结果
├── cli/                     # 命令行模块
│   └── src/main/java/com/loc/
│       └── CliMain.java            # CLI入口
├── idea-plugin/             # IDEA插件模块
│   └── src/main/
│       ├── java/com/loc/
│       │   ├── LocateSqlAction.java            # IDEA Action（快捷键 Ctrl+Alt+L）
│       │   ├── SqlLocatorToolWindowFactory.java # 右侧工具窗口
│       │   └── SqlLocatorHistory.java           # 查询历史记录
│       ├── resources/META-INF/
│       │   └── plugin.xml                       # 插件描述文件
│       └── resources/icons/
│           └── sql-locator.svg                  # 工具窗口图标
└── build/                   # 构建输出
    ├── slow-sql-locator.zip        # 插件安装包
    ├── verify-src/VerifyPlugin.java # 包结构/字节码/plugin.xml/Action 校验器
    └── DemoTest.java                # 演示项目用例验证
```

## 核心功能

### 1. SQL归一化 (SqlNormalizer)
- 去除注释和Hint
- 统一占位符格式（`#{x}`, `${x}`, `?`, `:1` → `:P`）
- 压缩IN列表
- 移除MyBatis动态标签（`<where>` 会被还原为 `WHERE` 关键字，避免条件丢失）
- 处理子查询/CTE：将括号内容替换为占位符，避免内部条件干扰主查询分析

### 2. SQL指纹与结构提取 (Fingerprint)
- **指纹**: 基于MD5的12位hex，用于精确匹配
- **骨架**: 提取表名和别名，如 `T_USER:U`, `T_ORDER:O`；支持 `schema.table` 多级表名（如 `CAPITAL.CAPITAL_INSURANCE_INVOICE:T`，不会把 schema 名误当表名）
- **WHERE列集合**: 提取 `WHERE` 条件中出现的**裸列名**（忽略表前缀和别名），例如 `T_USER.ID`、`U.ID`、`ID` 统一为 `ID`，用于集合级等值匹配
- **JOIN条件**: 提取 JOIN 关联的表集合以及 ON/USING 等式集合，等式两侧按字母序归一化，忽略顺序与别名差异

### 3. 索引构建 (IndexBuilder)
- 扫描MyBatis Mapper XML文件（`<select>`, `<update>`, `<insert>`, `<delete>`）
- 扫描Java注解（`@Select`, `@Update`, `@Delete`, `@Insert`, `@Query`）
- 生成索引文件 `.sql-locator-index`
- 每个索引条目额外保存 WHERE 列键、JOIN 表键、JOIN 条件键，供精确匹配使用

### 4. SQL定位 (SqlLocator)
四级匹配策略（v1.4/v1.5 起增强结构约束，避免“类型不同/表不同/条件不同也被搜出来”）：
- **L1 指纹命中**: 完全匹配，score=1.0
- **L2 骨架一致**: 语句类型相同、表集合相同（忽略别名）、WHERE 列集合相同、JOIN 表集合与条件均相同，score=0.9
- **L3 结构近似**: 语句类型相同、表集合相同、WHERE/JOIN 条件不完全相同但重合度 >= 0.5，score≈0.7
- **L4 弱相关**: 语句类型相同、表集合相同、条件重合度低（>=0.2），score≈0.4

硬性过滤规则：
- 语句类型必须一致（SELECT 不会匹配 INSERT/UPDATE/DELETE）
- 主表不一致或完全没有共同表的条目会被过滤
- WHERE 条件列集合不一致的条目会被过滤或大幅降权
- JOIN 关联的表/条件不一致的条目会被过滤或降权

## 产物位置与只读保证

### 只读保证（不修改任何源文件）
插件与 CLI 全程**只读**扫描源码：
- 读取方式仅 `Files.readAllBytes`（只读打开，读完即关闭，不加锁、不改属性）
- 不使用任何 IDEA 的写入 API（无 PSI / Document / WriteCommandAction / VirtualFile 写入）
- 不删除、不重命名、不移动、不改权限、不改时间戳任何文件
- 遍历不跟随符号链接，不会越出项目目录
- **唯一的写操作**：把索引写入项目根下的 `.sql-locator-index`（显式 `CREATE + TRUNCATE_EXISTING + WRITE`）

已验证：对含**只读属性**的源文件（XML / Java）扫描与查询均正常命中，说明插件不需要写权限即可工作。

### 产物位置
| 类型 | 位置 | 说明 |
|---|---|---|
| 索引文件 | `<项目根>/.sql-locator-index` | TSV 文本索引；插件首次使用时自动生成，可安全删除（下次会自动重建）|
| 查询历史 | `<项目根>/.sql-locator-history` | TSV 格式，保存最近最多 50 条查询记录 |
| 插件安装目录 | `<IDEA 配置目录>/plugins/slow-sql-locator/` | 由 IDEA 安装器解压管理，非插件自身写入 |
| 构建产物（仅开发期）| `build/classes/`、`build/slow-sql-locator/`、`build/slow-sql-locator.zip` | 打包过程产生，与插件运行无关 |

> 建议把 `.sql-locator-index` 和 `.sql-locator-history` 加入项目的 `.gitignore`，避免误提交。

## 使用方式

### 方式一：IntelliJ IDEA插件

#### 安装
1. 打开 IntelliJ IDEA
2. File > Settings > Plugins
3. 点击齿轮图标 > Install Plugin from Disk...
4. 选择: `build/slow-sql-locator.zip`

#### 使用
1. **右侧工具窗口**：点击右侧工具栏的 "Slow SQL Locator" 图标（放大镜），打开工具窗口
2. **历史与详情**：工具窗口上部左侧为历史列表，右侧为查询详情框；点击历史条目可在详情框查看完整信息（含命中结果列表）
3. **新建查询**：在底部 SQL 输入框粘贴 SQL，点击 "新建查询 (Ctrl+Alt+Q)" 按钮即可查询；也可在编辑器中右键或按 `Ctrl+Alt+Q` 打开对话框输入
4. **进度展示**：IDEA 底部会出现 **进度条**（"Locate Slow SQL"），显示“加载索引 / 扫描源文件 / 匹配中”等阶段
5. **结果弹窗**：查询完成后以弹窗展示定位结果；若失败，会展示异常信息
6. **历史记录**：右侧工具窗口会列出最近查询历史（时间、命中数、索引规模、SQL 摘要），点击条目可在详情框查看完整 SQL 与命中详情

> 首次使用若项目根目录没有 `.sql-locator-index`，插件会**自动扫描整个项目**建立索引（自动跳过 `build`/`target`/`out`/`dist`/`node_modules`/`.git`/`.gradle` 等目录），并把索引写到项目根目录，无需手动执行 CLI。
> 结果弹窗会显示索引规模（已扫描多少条 SQL），便于判断索引是否正常。
> 整个索引构建/查询过程在后台运行，UI 不会卡死，异常也会显式弹窗。

### 方式二：命令行

#### 构建索引
```bash
cd SqlPoint
java -cp "target\core-classes;target\cli-classes" com.loc.CliMain --build <源码目录>
```

示例：
```bash
java -cp "target\core-classes;target\cli-classes" com.loc.CliMain --build "target\test-sources"
```

#### 查询SQL
```bash
java -cp "target\core-classes;target\cli-classes" com.loc.CliMain "SELECT u.id, u.name FROM t_user u WHERE u.status = 1"
```

输出示例：
```
[☆骨架一致] com.test.UserMapper.java:@Select    @ com\test\UserMapper.java:7
```

#### grep兜底
当索引未命中时，自动生成grep命令：
```bash
grep -rniE '\bT_USER\b' --include=*.xml --include=*.java src/
```

## 构建插件

### 兼容性
- **运行环境**：插件字节码为 Java 8（class v52），可在 JBR 8 / 11 / 17 / 21 上加载
- **IDEA 版本**：2019.3 (193) 起，含 2025.3 (253) 及之后版本（plugin.xml 未设 until-build 上限）
- **构建环境**：JDK 9+（编译参数 `--release 8`，JDK 8 的 javac 不支持该参数）

### 构建步骤

#### Windows (PowerShell)
```powershell
# 直接运行即可，无需设置 IDEA_HOME（自动使用仓库内置 idea-sdk）
powershell -ExecutionPolicy Bypass -File build-plugin.ps1
```

#### Windows (CMD)
```cmd
build-plugin.bat
```

#### 手动构建
```bash
# 1. 编译核心模块（Java 8 字节码）
javac --release 8 -encoding UTF-8 -d build/classes core/src/main/java/com/loc/*.java

# 2. 编译插件模块（使用仓库内置 idea-sdk）
javac --release 8 -encoding UTF-8 -cp "build/classes;idea-sdk/lib/*" -d build/classes idea-plugin/src/main/java/com/loc/*.java

# 3. 打包JAR
cd build/classes
jar cf ../slow-sql-locator/lib/sql-locator-core.jar com

# 4. 复制plugin.xml
copy idea-plugin/src/main/resources/META-INF/plugin.xml build/slow-sql-locator/META-INF/

# 5. 创建ZIP包（必须用 jar，不能用 Compress-Archive——后者生成的 zip 用反斜杠分隔符，IDEA 会报 corrupted archive）
cd build
jar cMf slow-sql-locator.zip slow-sql-locator
```

## 配置说明

### 排除目录
索引构建时自动排除以下目录：
- `build/`
- `target/`
- `out/`
- `dist/`
- `node_modules/`
- `.git/`
- `.gradle/`
- `.svn/`
- `.idea/`
- `.workbuddy/`

### 索引文件
- 位置: 项目根目录下的 `.sql-locator-index`
- 格式: TSV（制表符分隔），v1.4 起为 9 列
- 字段: `fp`, `skelKey`, `key`, `path`, `line`, `type`, `whereColsKey`, `joinTablesKey`, `joinOnKey`

字段说明：
| 字段 | 含义 |
|---|---|
| `fp` | SQL 指纹（12位 MD5 hex） |
| `skelKey` | 表骨架键（表名:别名集合） |
| `key` | namespace/类名 + 语句 id |
| `path` | 源码文件相对路径 |
| `line` | 语句所在行号 |
| `type` | 语句类型（SELECT/UPDATE/INSERT/DELETE） |
| `whereColsKey` | WHERE 条件列集合键（排序后拼接） |
| `joinTablesKey` | JOIN 关联表集合键 |
| `joinOnKey` | JOIN ON/USING 条件等式集合键 |

## 技术细节

### SQL匹配算法（v1.4）
1. **精确匹配**: 基于SQL指纹（MD5）
2. **结构匹配**: 基于表骨架（FROM/JOIN/UPDATE/INSERT/INTO后的表名）
3. **条件匹配**: 基于 WHERE 条件列集合的等值比较（忽略顺序与别名差异）
4. **关联匹配**: 基于 JOIN 表集合 + ON/USING 等式集合的比较
5. **模糊匹配**: 基于表名与条件重合度计算

### 性能优化
- 索引缓存：进程内复用，避免重复加载
- 懒加载：首次使用时构建索引
- 增量更新：支持重新构建索引

## 示例

### 测试SQL
```sql
SELECT u.id, u.name, u.email
FROM t_user u
WHERE u.status = :1
  AND u.name LIKE CONCAT('%', :2, '%')
```

### 预期输出
```
[★指纹命中] com.test.TestMapper.selectUser    @ com/test/TestMapper.xml:4
[☆骨架一致] com.test.UserMapper:@Select       @ com/test/UserMapper.java:7
```

## 故障排查

### 问题1: 索引为空
- 插件首次使用会自动建索引；若弹窗提示"已扫描 0 条 SQL"，说明项目里没找到 Mapper XML / 注解 SQL
- 检查源码目录是否正确、XML 是否为 MyBatis 标准 `<mapper namespace="...">` 格式（namespace 与语句必须成对闭合）
- 确认没有被排除目录规则跳过（`build`/`target`/`out`/`dist`/`node_modules`/`.git`/`.gradle`/`.idea`）
- 需要强制重建时，删除项目根的 `.sql-locator-index` 后重试

### 问题2: 插件无法加载
- 确认IDEA版本兼容性（2019.3+，含 2025.3）
- 确认 class 字节码为 Java 8（major=52），高版本字节码会被 JBR 拒绝加载
- 检查plugin.xml格式是否正确
- 查看IDEA日志获取详细错误信息

### 问题3: 匹配结果不准确
- 确认查询 SQL 与源码 SQL 的**语句类型一致**：v1.4 起，SELECT 不会匹配 INSERT/UPDATE/DELETE
- 确认查询 SQL 与源码 SQL 的**主表一致**：只有主表（FROM/UPDATE/INSERT/INTO 后的第一张表）相同的源码才会进入结果
- 确认 WHERE 条件列集合符合预期：别名/表前缀不影响识别，`T_USER.ID` 与 `ID` 视为同一列
- 检查 SQL 归一化是否正确
- 验证索引文件内容
- 如需调整松紧度，可修改 `SqlLocator.java` 中的 L3/L4 阈值（默认 L3>=0.5，L4>=0.2）

## 许可证

MIT License

## 贡献

欢迎提交Issue和Pull Request！
