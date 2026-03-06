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
        new MyDictHelper((name, value) -> {
            invocations.incrementAndGet();
            return name + ":" + value;
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
        new MyDictHelper((name, value) -> {
            cachedInvocations.incrementAndGet();
            return name + ":" + value;
        }, enabled);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1");
        assertThat(cachedInvocations).hasValue(1);

        MyDictCacheProperties disabled = new MyDictCacheProperties();
        disabled.setEnabled(false);

        AtomicInteger uncachedInvocations = new AtomicInteger();
        new MyDictHelper((name, value) -> {
            int current = uncachedInvocations.incrementAndGet();
            return name + ":" + value + ":" + current;
        }, disabled);

        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1:1");
        assertThat(MyDictHelper.getDesc("status", "1")).isEqualTo("status:1:2");
        assertThat(uncachedInvocations).hasValue(2);
    }
}
