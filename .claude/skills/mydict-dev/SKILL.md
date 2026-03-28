---
name: mydict-dev
description: |
  mydict-spring-boot-starter 开发指南。当遇到以下任何场景时必须使用此 skill：
  - 实体类/VO 中存在数字类型字段（Integer、Long、Short、Byte）或字符串枚举字段，其值代表字典含义（如状态、类型、性别、等级等）
  - 用户提到需要"字典描述"、"字典转换"、"枚举描述"、"状态描述"等需求
  - 代码中出现 @MyDict、IMyDict、MyDictHelper、MyDictCacheProperties 任何一个标识
  - 用户需要给字段自动生成 xxxDesc 描述字段
  - 配置 mydict.cache.* 相关属性
  - 字典值到文本描述的映射场景（如 1→"启用"，0→"禁用"）
  这是一个基于 javac 注解处理器的编译期代码生成工具，不是运行时反射，请务必使用此 skill 确保注解用法、命名规则和集成模式的正确性。
---

# mydict-spring-boot-starter 开发指南

本项目通过**编译期注解处理**自动生成字典描述字段，解决实体类中数字/枚举字段需手写 `xxxDesc` 描述字段的痛点。标注 `@MyDict` 后，编译时自动生成 `xxxDesc` 字段及其 getter/setter，运行时调用用户实现的 `IMyDict` 查询描述并缓存结果。

---

## 一、快速识别触发场景

**遇到以下字段定义，应立即想到使用 `@MyDict`：**

```java
// ❌ 传统写法：需手动维护 statusDesc 字段
private Integer status;          // 1=启用, 0=禁用
private String statusDesc;       // 手写，繁琐且容易遗忘

// ✅ MyDict 写法：编译期自动生成 statusDesc
@MyDict(type = "user_status")
private Integer status;          // 编译后自动产生 statusDesc 字段和方法
```

**核心判断标准**：当字段值本身只是一个代号（整数或字符串），需要通过字典表/配置/服务查询才能得到人类可读的描述时，就应该使用 `@MyDict`。

---

## 二、@MyDict 注解参数

### 参数说明

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `type` | String | 是 | — | 字典类型码，传给 `IMyDict.getDesc(type, value)` |
| `defaultDesc` | String | 否 | `""` | 查询结果为空时的默认值 |
| `camelCase` | boolean | 否 | `true` | 全小写字段名时，生成驼峰还是蛇形描述字段名 |
| `descFieldAnnotations` | FieldAnnotation[] | 否 | `{}` | 给生成的描述字段添加自定义注解（如 Swagger） |

### 基础用法

```java
@Data
public class UserVO {
    // 最简用法
    @MyDict(type = "user_status")
    private Integer status;
    // → 自动生成 statusDesc 字段 + getStatusDesc() + setStatusDesc()

    // 带默认描述（字典查不到时返回"未知"）
    @MyDict(type = "user_gender", defaultDesc = "未知性别")
    private Integer gender;
    // → getGenderDesc() 查不到时返回 "未知性别"

    // 字符串枚举字段同样支持
    @MyDict(type = "goods_type")
    private String goodsType;
    // → 自动生成 goodsTypeDesc
}
```

---

## 三、描述字段命名规则

命名规则按**优先级从高到低**自动识别：

| 原始字段名 | 识别规则 | 生成的描述字段名 |
|-----------|---------|----------------|
| `user_status` | 包含下划线（小写） | `user_status_desc` |
| `USER_STATUS` | 包含下划线（全大写） | `USER_STATUS_DESC` |
| `userName` | 大小写混合（驼峰） | `userNameDesc` |
| `status` | 全小写 + `camelCase=true`（默认） | `statusDesc` |
| `status` | 全小写 + `camelCase=false` | `status_desc` |

**关键原则**：
- 字段名中只要含有 `_`，无论 `camelCase` 配置，都优先走蛇形规则
- 大小写混合（如 `userName`）强制驼峰，不受 `camelCase` 影响
- 只有全小写字段名才受 `camelCase` 参数控制

---

## 四、集成步骤

### Step 1：Maven 依赖

```xml
<dependency>
    <groupId>io.github.canjiemo</groupId>
    <artifactId>mydict-spring-boot-starter</artifactId>
    <version>1.0.7-jdk21</version>
</dependency>
```

### Step 2：Maven 编译器配置（JDK 21 必需）

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <source>21</source>
        <target>21</target>
        <fork>true</fork>
        <compilerArgs>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
            <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED</arg>
            <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### Step 3：实现 IMyDict 接口（必须提供 Spring Bean）

```java
@Component
public class DictServiceImpl implements IMyDict {

    @Autowired
    private YourDictRepository dictRepository;  // 替换成你的数据源

    @Override
    public String getDesc(String type, String value) {
        // 返回 null 表示未找到（框架缓存此 null，不穿透）
        return dictRepository.findDesc(type, value);
    }
}
```

**注意**：如果没有提供 `IMyDict` 的 Bean，Spring 启动时会抛出 `IllegalStateException`，给出友好提示。

### Step 4：在实体/VO 类上使用注解

```java
@Data
public class OrderVO {
    @MyDict(type = "order_status", defaultDesc = "未知状态")
    private Integer status;

    @MyDict(type = "pay_type")
    private String payType;
}
```

### Step 5：编译

```bash
mvn clean compile
# 或在 IDEA 中 Build → Build Project
```

编译后，`OrderVO` 自动包含 `statusDesc`、`payType` 对应的 `payTypeDesc` 字段。

### Step 6（可选）：配置缓存

```yaml
mydict:
  cache:
    enabled: true        # 是否启用 Caffeine 缓存（默认 true）
    ttl: 30              # 缓存过期时间，单位秒（默认 30）
    max-size: 10000      # 最大缓存条目数（默认 10000）
    record-stats: false  # 是否记录命中率等统计（默认 false）
```

---

## 五、编译期生成效果

**编译前（你写的代码）**：
```java
@Data
public class UserVO {
    @MyDict(type = "user_status", defaultDesc = "未知")
    private Integer status;
}
```

**编译后（框架自动注入的代码，无需手写）**：
```java
@Data
public class UserVO {
    private Integer status;

    @TableField(exist = false)  // 自动添加，MyBatis-Plus 不映射此字段到 DB
    private String statusDesc;

    public String getStatusDesc() {
        String descStr = MyDictHelper.getDesc("user_status", getStatus());
        return (descStr != null && !descStr.isEmpty()) ? descStr : "未知";
    }

    public void setStatusDesc(String statusDesc) {
        this.statusDesc = statusDesc;
    }
}
```

---

## 六、高级用法：给描述字段添加自定义注解

场景：需要给生成的 `xxxDesc` 字段添加 Swagger `@Schema` 或其他注解。

```java
import io.github.canjiemo.tools.dict.MyDict;
import io.github.canjiemo.tools.dict.entity.FieldAnnotation;
import io.github.canjiemo.tools.dict.entity.Var;
import io.github.canjiemo.tools.dict.entity.VarType;

@MyDict(
    type = "goods_type",
    defaultDesc = "未知类型",
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
// 生成的 goodsTypeDesc 字段带有 @Schema(description="商品类型描述", hidden=true)
```

### @FieldAnnotation 和 @Var 参数

**@FieldAnnotation**：
| 参数 | 类型 | 说明 |
|------|------|------|
| `fullAnnotationName` | String | 目标注解的完整类名（含包路径） |
| `vars` | Var[] | 注解的成员配置数组 |

**@Var**：
| 参数 | 类型 | 说明 |
|------|------|------|
| `varType` | VarType | 值的类型枚举 |
| `varName` | String | 注解成员名 |
| `varValue` | String | 单个值（与 varValues 二选一） |
| `varValues` | String[] | 数组值（如 String[] 类型的注解成员） |

**VarType 枚举值**：

| 枚举 | 对应 Java 类型 | 示例 varValue |
|------|--------------|---------------|
| `STRING` | String | `"hello"` |
| `BOOLEAN` | boolean | `"true"` / `"false"` |
| `INT` | int | `"42"` |
| `LONG` | long | `"100L"` |
| `FLOAT` | float | `"3.14"` |
| `DOUBLE` | double | `"3.14"` |
| `BYTE` | byte | `"127"` |
| `SHORT` | short | `"32767"` |
| `CHAR` | char | `"A"` |
| `CLASS` | Class<?> | `"java.lang.String"` |
| `ENUM` | 枚举 | `"枚举类全限定名.枚举值名"` |

---

## 七、缓存管理 API

`MyDictHelper` 提供静态方法管理缓存，可在需要刷新字典数据时调用：

```java
// 清空所有字典缓存
MyDictHelper.clearCache();

// 清空指定字典类型的缓存（精准清除，不影响其他类型）
MyDictHelper.clearCache("user_status");

// 查看缓存统计（需 record-stats=true）
Optional<CacheStats> stats = MyDictHelper.getCacheStats();
stats.ifPresent(s -> {
    System.out.println("命中率：" + s.hitRate());
    System.out.println("命中次数：" + s.hitCount());
    System.out.println("未命中次数：" + s.missCount());
});
```

**缓存设计要点**：
- 使用 `Optional<String>` 包装缓存结果，字典查不到时缓存 `Optional.empty()`，**防止缓存穿透**
- 缓存 Key 格式：`type.length() + ":" + type + value`，**防止 Key 碰撞**（如 `type="a:b", value="c"` 与 `type="a", value=":bc"` 不会碰撞）
- 支持运行时动态刷新，无需重启服务

---

## 八、常见问题与陷阱

### 1. 手写的 getter/setter 不会被覆盖

如果你已经手写了 `getStatusDesc()` 或 `statusDesc` 字段，注解处理器会跳过生成，不会产生编译冲突：

```java
@MyDict(type = "user_status")
private Integer status;

// 如果你手写了这个方法，处理器不再生成，以手写为准
public String getStatusDesc() {
    return "自定义逻辑: " + status;
}
```

### 2. IDEA 需要安装插件或触发重新编译

IDEA 的增量编译有时不会触发注解处理器。如果 `getXxxDesc()` 方法红线报错，执行：
- `Build → Rebuild Project`，或
- `mvn clean compile`

### 3. @TableField(exist=false) 自动添加

框架检测到 MyBatis-Plus 在类路径时，会自动给生成的 `xxxDesc` 字段添加 `@TableField(exist = false)`，无需手动处理。如果没有 MyBatis-Plus，不会添加。

### 4. null 值的处理

```java
vo.setStatus(null);
vo.getStatusDesc();  // 直接返回 null，不会调用 IMyDict
```

源字段为 `null` 时，`getDesc` 不会调用字典服务，直接返回 `null`（即使设置了 `defaultDesc`，默认值仅在字典服务返回空字符串或 null 时生效，而不是源字段为 null 时）。

### 5. 不同类型相同值独立缓存

```java
MyDictHelper.getDesc("status", "1");  // key = "6:status1"
MyDictHelper.getDesc("gender", "1");  // key = "6:gender1"
// 两个缓存 key 不同，互不影响
```

---

## 九、配置属性速查

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `mydict.cache.enabled` | boolean | `true` | 是否启用 Caffeine 缓存 |
| `mydict.cache.ttl` | long | `30` | 缓存过期时间（秒） |
| `mydict.cache.max-size` | long | `10000` | 缓存最大条目数 |
| `mydict.cache.record-stats` | boolean | `false` | 是否记录命中率统计 |

---

## 十、完整实战示例

```java
// 1. 实现字典服务
@Component
public class MyDictImpl implements IMyDict {
    @Autowired
    private SysDictItemMapper dictItemMapper;

    @Override
    public String getDesc(String type, String value) {
        return dictItemMapper.selectLabel(type, value); // null = 未找到
    }
}

// 2. 在 VO 中使用
@Data
public class OrderListVO {
    private Long id;
    private String orderNo;

    @MyDict(type = "order_status", defaultDesc = "未知状态")
    private Integer status;           // → statusDesc

    @MyDict(type = "pay_type", defaultDesc = "未知方式")
    private Integer payType;          // → payTypeDesc

    @MyDict(type = "delivery_type")
    private String delivery_type;     // → delivery_type_desc（含下划线，蛇形）

    @MyDict(type = "priority_level")
    private Integer priorityLevel;    // → priorityLevelDesc（驼峰）
}

// 3. 使用时直接调用（编译后生效）
OrderListVO vo = new OrderListVO();
vo.setStatus(1);
vo.setPayType(2);
System.out.println(vo.getStatusDesc());     // "已支付"（来自字典）
System.out.println(vo.getPayTypeDesc());    // "微信支付"（来自字典）

// 4. 刷新字典（如后台更新了字典数据）
MyDictHelper.clearCache("order_status");   // 精准刷新
// 或 MyDictHelper.clearCache();            // 全量刷新
```
