# MyDict Spring Boot Starter

MyDict 是一个面向 Spring Boot 的字典描述字段自动化方案，用于把业务中的字典值字段在编译期扩展为可直接返回前端的描述字段，例如 `status -> statusDesc`、`type -> typeDesc`。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.canjiemo/mydict-spring-boot-starter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.canjiemo/mydict-spring-boot-starter)

支持 JDK 21+ 和 Spring Boot 3.x。

## 📌 项目介绍

在业务系统里，状态、类型、级别、性别这类字段通常只存储字典值，前端真正需要的却是可直接展示的字典文案。

MyDict 通过注解处理器在编译期间自动生成描述字段和访问方法，并在运行时结合 Spring Bean 完成字典查询。它的目标不是替代业务字典系统，而是把"字典值转展示文案"这件重复而琐碎的工作，从业务代码里抽离出来。

## ✨ 功能特性

- 🎯 **低配置接入** - 无需额外 VM 参数，Maven 仅需一次性编译参数
- ⚡ **编译时处理** - 编译期自动生成字典描述字段
- 🔧 **Spring Boot 3** - 全面支持最新版本
- 🌟 **JDK21+** - 拥抱现代Java生态
- 🔗 **MyBatis-Plus集成** - 自动添加`@TableField(exist = false)`
- 🎨 **自定义注解** - 支持在生成字段上添加任意注解
- 💾 **Caffeine缓存** - 自动缓存字典查询结果，可配置TTL和容量
- 🔤 **智能命名** - 自动识别蛇形/驼峰命名，支持camelCase开关
- 🧩 **多模块架构** - processor、core、spring-boot 职责分离
- 🛡️ **边界场景已覆盖** - 无 getter、`final` 字段、手写 desc 访问器等场景可用
- 💡 **IDEA 插件支持** - 实时识别生成字段，无需编译即可代码补全和跳转导航

## 🧱 项目结构

- `mydict-core`：注解定义、运行时帮助类（纯 Java，无 Spring 依赖）
- `mydict-processor`：编译期注解处理器，负责生成 `xxxDesc` 字段和方法
- `mydict-spring-boot`：Spring Boot 自动配置 + starter（artifactId：`mydict-spring-boot-starter`）

### 本地构建

```bash
mvn clean test
mvn -pl mydict-spring-boot -am package
```

## 📋 版本兼容性

| 版本 | JDK要求 | Spring Boot | 说明 |
|------|---------|-------------|------|
| 1.0.6-jdk21 | **JDK21-24+** | 3.0+ | `getDesc` value 为 null 时直接返回 null |
| 1.0.5-jdk21 | JDK21-24+ | 3.0+ | `@MyDict(type=...)` 统一注解属性 |
| 1.0.4-jdk21 | JDK21-24+ | 3.0+ | 历史版本 |

> **⚠️ 升级注意**：从 1.0.4 升级到 1.0.5，需将注解属性 `value`/`name` 统一改为 `type`：
> ```java
> // 旧写法
> @MyDict("user_status")
> @MyDict(name = "user_status")
> // 新写法
> @MyDict(type = "user_status")
> ```

## 🎪 代码示例

### 编译前：
```java
@Data
public class UserVO {
    @MyDict(type = "goods_type", defaultDesc = "未知类型")
    private Integer goodsType = 1;

    @MyDict(type = "user_status")
    private String status = "ACTIVE";
}
```

### 编译后自动生成：
```java
@Data
public class UserVO {
    private Integer goodsType = 1;

    @TableField(exist = false) // MyBatis-Plus 环境下自动添加
    private String goodsTypeDesc;

    private String status = "ACTIVE";

    @TableField(exist = false)
    private String statusDesc;

    public String getGoodsTypeDesc() {
        String descStr = MyDictHelper.getDesc("goods_type", getGoodsType());
        return (descStr != null && !descStr.isEmpty()) ? descStr : "未知类型";
    }

    public String getStatusDesc() {
        String descStr = MyDictHelper.getDesc("user_status", getStatus());
        return (descStr != null && !descStr.isEmpty()) ? descStr : "";
    }

    public void setGoodsTypeDesc(String goodsTypeDesc) { this.goodsTypeDesc = goodsTypeDesc; }
    public void setStatusDesc(String statusDesc) { this.statusDesc = statusDesc; }
}
```

> 源字段不要求手写 getter；处理器会优先调用 getter，不存在时回退为直接读字段。

## 🚀 快速开始

### 1. 添加依赖

> 最新版本请参考顶部徽章或 [Maven Central](https://central.sonatype.com/artifact/io.github.canjiemo/mydict-spring-boot-starter)。

```xml
<dependencies>
    <dependency>
        <groupId>io.github.canjiemo</groupId>
        <artifactId>mydict-spring-boot-starter</artifactId>
        <version>1.0.6-jdk21</version>
    </dependency>
</dependencies>
```

### 2. ⚠️ 配置Maven编译器（必需！）

由于JDK 21+的模块化限制，注解处理器需要访问javac内部API。**必须**在项目的`pom.xml`中添加以下配置：

#### JDK 21-23 配置

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <fork>true</fork>
                <compilerArgs>
                    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
                    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
                    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
                    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

#### ⚠️ JDK 24+ 额外配置

```xml
<compilerArgs>
    <!-- JDK 21-23 -->
    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
    <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
    <!-- JDK 24+: 额外 opens -->
    <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
    <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
    <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
    <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
    <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
    <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
</compilerArgs>
```

### 3. 实现字典查询接口

```java
@Component
public class DictServiceImpl implements IMyDict {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * @param type  字典类型码（如 "user_status"）
     * @param value 字典值（如 "1"）
     * @return 字典描述
     */
    @Override
    public String getDesc(String type, String value) {
        String key = "dict:" + type + ":" + value;
        return redisTemplate.opsForValue().get(key);
    }
}
```

### 4. 在实体类上使用注解

```java
@Data
public class User {
    @MyDict(type = "user_status", defaultDesc = "未知状态")
    private Integer status;

    @MyDict(type = "user_type")
    private String type;
}
```

### 5. 编译运行

```bash
mvn clean compile
```

编译成功后，`User`类会自动生成：
- `private String statusDesc;` 字段
- `private String typeDesc;` 字段
- 对应的getter/setter方法

## 💾 缓存配置

MyDict 内置 Caffeine 缓存以提升字典查询性能。默认启用，可通过配置调整：

```yaml
mydict:
  cache:
    enabled: true        # 是否启用缓存（默认：true）
    ttl: 30             # 缓存过期时间，单位秒（默认：30）
    max-size: 10000     # 最大缓存条目数（默认：10000）
    record-stats: false # 是否记录缓存统计（默认：false）
```

### 缓存管理

```java
// 清空所有缓存（字典数据更新后调用）
MyDictHelper.clearCache();

// 清除指定字典类型的缓存
MyDictHelper.clearCache("user_status");

// 获取缓存统计信息（需要 record-stats=true）
MyDictHelper.getCacheStats().ifPresent(stats ->
    System.out.println("缓存命中率: " + stats.hitRate())
);
```

## 🔤 字段命名策略

### camelCase 参数

```java
@MyDict(type = "status", camelCase = true)   // 生成：statusDesc
@MyDict(type = "status", camelCase = false)  // 生成：status_desc
```

### 智能命名规则（优先级从高到低）

| 原字段名 | camelCase | 生成字段名 | 说明 |
|---------|-----------|-----------|------|
| `user_status` | true/false | `user_status_desc` | ①包含下划线，生成蛇形 |
| `userName` | true/false | `userNameDesc` | ②大小写混合，生成驼峰 |
| `status` | **true** | `statusDesc` | ③全小写，遵循开关 |
| `status` | **false** | `status_desc` | ③全小写，遵循开关 |
| `SEX_TYPE` | true/false | `SEX_TYPE_DESC` | ①全大写蛇形 |

## 🔧 高级用法

### 自定义注解支持

```java
@MyDict(
    type = "goods_type",
    defaultDesc = "默认商品",
    descFieldAnnotations = {
        @FieldAnnotation(
            fullAnnotationName = "io.swagger.v3.oas.annotations.media.Schema",
            vars = {
                @Var(varType = VarType.STRING, varName = "description", varValue = "商品类型描述"),
                @Var(varType = VarType.BOOLEAN, varName = "hidden", varValue = "true")
            }
        )
    }
)
private Integer goodsType;
```

生成结果：
```java
@Schema(description = "商品类型描述", hidden = true)
@TableField(exist = false)
private String goodsTypeDesc;
```

补充说明：
- `descFieldAnnotations` 用于给自动生成的 `xxxDesc` 字段追加任意注解
- `VarType` 支持：`STRING`、`BOOLEAN`、`INT`、`LONG`、`DOUBLE`、`FLOAT`、`BYTE`、`SHORT`、`CHAR`、`CLASS`、`ENUM`
- 数组类型使用 `varValues`，例如 `String[]`、`Class<?>[]`、枚举数组

## 🛠️ 故障排除

### 编译报错：`IllegalAccessError: cannot access class com.sun.tools.javac.api.JavacTrees`

**原因**：缺少步骤2的Maven编译器配置。

**解决**：在项目的`pom.xml`中添加`maven-compiler-plugin`配置（参见步骤2）。

---

### JDK 24 编译成功但字段没有生成

**原因**：JDK 24 模块访问限制更严格，需要额外的 `--add-opens` 参数。

**解决**：使用上方 JDK 24+ 的完整配置。

---

### 生成的字段在 IDE 中看不到（显示红色波浪线）

这是正常现象，与 Lombok 一样，字段是在编译期生成的。

**解决**：委托给 Maven：`Settings` → `Build Tools` → `Maven` → `Runner` → 勾选 `Delegate IDE build/run actions to Maven`

---

### 字典查询返回null

- 检查`IMyDict`实现类是否被Spring扫描到（需要 `@Component` 等注解）
- 检查字典数据是否正确存储在数据源中

---

### 完整的pom.xml示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.0</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>1.0.0</version>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.canjiemo</groupId>
            <artifactId>mydict-spring-boot-starter</artifactId>
            <version><!-- 最新版本见顶部徽章 --></version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <fork>true</fork>
                    <compilerArgs>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## 💡 IDEA 插件

MyDict 提供了专属的 IntelliJ IDEA 插件 **[mydict-intellij-plugin](https://github.com/canjiemo/mydict-intellij-plugin)**，提供类似 Lombok 的 IDE 体验：

- **实时字段识别** - 无需编译，`xxxDesc` 字段和 `getXxxDesc()`/`setXxxDesc()` 方法立即可用
- **代码补全** - 在 IDE 中直接输入 `dto.getStatusDesc()` 不会显示红色报错
- **导航跳转** - `Cmd+Click`（或 `Ctrl+Click`）生成的方法会跳转到源字段
- **开启提醒** - 首次检测到 MyDict 依赖但注解处理器未启用时，自动弹出提醒

**安装方式：**

在 JetBrains Marketplace 搜索 `MyDict` 安装，或从 GitHub Releases 下载 ZIP 手动安装。

> **注意：** 插件仅负责 IDE 实时识别，运行时字段生成仍需开启注解处理器（`Settings → Build → Compiler → Annotation Processors → Enable annotation processing`）。

---

## 📝 更新日志

### 1.0.6-jdk21 当前版本
- ✅ `getDesc` value 为 null 时直接返回 null，修复空值处理逻辑

### 1.0.5-jdk21
- ✅ `@MyDict` 注解属性统一为 `type`，语义与数据字典领域一致（破坏性变更）
- ✅ `IMyDict.getDesc` 参数由 `Object` 改为 `String`，接口契约与实现一致
- ✅ `getCacheStats()` 返回 `Optional<CacheStats>`，避免 NPE
- ✅ `mydict-core` 移除 Spring 依赖，成为纯 Java 模块
- ✅ autoconfigure 与 starter 两模块合并为 `mydict-spring-boot`，简化依赖树
- ✅ 缓存配置 `@ConfigurationProperties` 绑定移至 Spring Boot 模块
- ✅ `MyDictHelper` 静态字段添加 `volatile`，消除多线程竞态风险
- ✅ 清理处理器冗余代码（`getVarName`、重复分支逻辑、`anyMatch` 替换）
- ✅ 发布 IDEA 插件 `mydict-intellij-plugin`，实时识别生成字段

### 1.0.4-jdk21
- ✅ 原生支持 IDEA 增量编译（自动解包 Proxy）
- ✅ 多模块架构拆分
- ✅ 集成 Caffeine 缓存
- ✅ 智能命名识别（蛇形/驼峰）

### 1.2（历史版本）
- 支持JDK8和Spring Boot 2.x
- 需要手动IDEA配置

---

💡 **享受低配置、编译期生成字典字段的开发体验。**
