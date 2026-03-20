package io.github.canjiemo.tools.dict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MyDictHelperTest {

    @BeforeEach
    void resetCache() {
        MyDictHelper.clearCache();
    }

    @Test
    void cachesRepeatedLookupsWhenCacheEnabled() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        properties.setTtl(60);

        AtomicInteger invocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            invocations.incrementAndGet();
            return type + ":" + value;
        }, properties);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1");
        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1");
        assertThat(invocations).hasValue(1);

        MyDictHelper.clearCache("status");

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1");
        assertThat(invocations).hasValue(2);
    }

    @Test
    void disablingCacheResetsPreviousCacheState() {
        MyDictCacheProperties enabled = new MyDictCacheProperties();
        enabled.setEnabled(true);

        AtomicInteger cachedInvocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            cachedInvocations.incrementAndGet();
            return type + ":" + value;
        }, enabled);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1");
        assertThat(cachedInvocations).hasValue(1);

        MyDictCacheProperties disabled = new MyDictCacheProperties();
        disabled.setEnabled(false);

        AtomicInteger uncachedInvocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            int current = uncachedInvocations.incrementAndGet();
            return type + ":" + value + ":" + current;
        }, disabled);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1:1");
        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1:2");
        assertThat(uncachedInvocations).hasValue(2);
    }

    @Test
    void cacheKeysDoNotCollideWhenTypeContainsColon() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        new MyDictHelper((type, value) -> type + "|" + value, properties);

        // type="a:b", value="c"  和  type="a", value=":bc" 若用 type+":"+value 拼 key 会碰撞
        String r1 = MyDictHelper.getDesc("a:b", "c");
        String r2 = MyDictHelper.getDesc("a", ":bc");

        assertThat(r1).isEqualTo("a:b|c");
        assertThat(r2).isEqualTo("a|:bc");
    }

    @Test
    void nullSentinelIsNotLeakedAsRealValue() {
        // NULL_SENTINEL ("\u0000") 是内部实现细节，不能作为真实描述值返回给调用方
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        new MyDictHelper((type, value) -> "\u0000", properties); // 字典真实返回 "\u0000"

        String result = MyDictHelper.getDesc("status", "1");
        // 期望："\u0000" 是合法的字典值，应该被如实返回
        // 当前Bug：NULL_SENTINEL.equals(cached) 为 true，会被错误地当成 null 返回
        assertThat(result).isEqualTo("\u0000");
    }

    @Test
    void clearCacheByTypeAlsoRemovesSentinelEntries() {
        // clearCache(type) 应该把该 type 下缓存的哨兵值也一并清除
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        AtomicInteger invocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            invocations.incrementAndGet();
            return null; // 字典里没有这个值
        }, properties);

        MyDictHelper.getDesc("status", "999"); // 第1次：回源，缓存 sentinel
        MyDictHelper.getDesc("status", "999"); // 第2次：命中缓存，不回源
        assertThat(invocations).hasValue(1);

        MyDictHelper.clearCache("status"); // 清除该 type 的缓存

        MyDictHelper.getDesc("status", "999"); // 应该再次回源
        assertThat(invocations).hasValue(2);
    }

    @Test
    void getCacheStatsReturnsEmptyWhenCacheDisabled() {
        MyDictCacheProperties disabled = new MyDictCacheProperties();
        disabled.setEnabled(false);
        new MyDictHelper((type, value) -> "", disabled);

        assertThat(MyDictHelper.getCacheStats()).isEmpty();
    }

    @Test
    void returnsNullWhenValueIsNull() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        new MyDictHelper((type, value) -> "should_not_be_called", properties);

        assertThat(MyDictHelper.getDesc("status", null)).isNull();
    }

    @Test
    void doesNotPenetrateCacheWhenDictReturnsNull() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        AtomicInteger invocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            invocations.incrementAndGet();
            return null; // 字典里不存在这个 key
        }, properties);

        MyDictHelper.getDesc("status", "999");
        MyDictHelper.getDesc("status", "999");
        MyDictHelper.getDesc("status", "999");

        // 期望：缓存了 null 结果，只查询一次
        // 当前Bug：Caffeine 不缓存 null，每次都穿透，实际调用 3 次
        assertThat(invocations).hasValue(1);
    }

    @Test
    void getCacheStatsReturnsPresentWhenCacheEnabled() {
        MyDictCacheProperties enabled = new MyDictCacheProperties();
        enabled.setEnabled(true);
        enabled.setRecordStats(true);
        new MyDictHelper((type, value) -> type + ":" + value, enabled);

        MyDictHelper.getDesc("status", "1");

        assertThat(MyDictHelper.getCacheStats()).isPresent();
    }

    @Test
    void returnsNullWhenCacheDisabledAndDictReturnsNull() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(false);
        new MyDictHelper((type, value) -> null, properties);

        assertThat(MyDictHelper.getDesc("status", "999")).isNull();
    }

    @Test
    void cachesEmptyStringReturnedByDict() {
        // 空字符串是合法字典值，应被缓存，不应每次回源
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        AtomicInteger invocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            invocations.incrementAndGet();
            return "";
        }, properties);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("");
        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void clearCacheByTypeDoesNotAffectOtherTypes() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        AtomicInteger invocations = new AtomicInteger();
        new MyDictHelper((type, value) -> {
            invocations.incrementAndGet();
            return type + ":" + value;
        }, properties);

        MyDictHelper.getDesc("status", "1");
        MyDictHelper.getDesc("type", "2");
        assertThat(invocations).hasValue(2);

        MyDictHelper.clearCache("status");

        MyDictHelper.getDesc("status", "1"); // status 已清，回源
        MyDictHelper.getDesc("type", "2");   // type 仍在缓存，不回源
        assertThat(invocations).hasValue(3);
    }

    @Test
    void convertsVariousValueTypesToString() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(false);
        new MyDictHelper((type, value) -> "got:" + value, properties);

        assertThat(MyDictHelper.getDesc("t", 42)).isEqualTo("got:42");
        assertThat(MyDictHelper.getDesc("t", 3L)).isEqualTo("got:3");
        assertThat(MyDictHelper.getDesc("t", true)).isEqualTo("got:true");
        assertThat(MyDictHelper.getDesc("t", 1.5f)).isEqualTo("got:1.5");
    }

    @Test
    void getCacheStatsReturnsPresentWhenCacheEnabledEvenWithoutRecordStats() {
        // cache 开启但 recordStats=false 时，getCacheStats 仍返回 Present（统计全为零）
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        properties.setRecordStats(false);
        new MyDictHelper((type, value) -> type + ":" + value, properties);

        MyDictHelper.getDesc("status", "1");

        assertThat(MyDictHelper.getCacheStats()).isPresent();
        assertThat(MyDictHelper.getCacheStats().get().hitCount()).isZero();
    }

    @Test
    void differentTypesWithSameValueAreCachedSeparately() {
        MyDictCacheProperties properties = new MyDictCacheProperties();
        properties.setEnabled(true);
        new MyDictHelper((type, value) -> type + "=" + value, properties);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status=1");
        assertThat(MyDictHelper.getDesc("gender", "1")).isEqualTo("gender=1");
    }
}
