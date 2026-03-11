package io.github.canjiemo.tools.dict;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DictAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DictAutoConfiguration.class))
            .withBean(IMyDict.class, () -> (name, value) -> name + ":" + value);

    @Test
    void bindsCachePropertiesFromConfiguration() {
        contextRunner
                .withPropertyValues(
                        "mydict.cache.enabled=false",
                        "mydict.cache.ttl=45",
                        "mydict.cache.max-size=123",
                        "mydict.cache.record-stats=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MyDictCacheProperties.class);
                    assertThat(context).hasSingleBean(MyDictHelper.class);

                    MyDictCacheProperties properties = context.getBean(MyDictCacheProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getTtl()).isEqualTo(45);
                    assertThat(properties.getMaxSize()).isEqualTo(123);
                    assertThat(properties.isRecordStats()).isTrue();
                });
    }
}
