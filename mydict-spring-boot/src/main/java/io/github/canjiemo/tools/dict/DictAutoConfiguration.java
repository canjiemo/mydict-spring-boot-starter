package io.github.canjiemo.tools.dict;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyDict 自动配置类
 *
 * @author canjiemo
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
public class DictAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "mydict.cache")
    public MyDictCacheProperties myDictCacheProperties() {
        return new MyDictCacheProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public MyDictHelper myDictHelper(IMyDict myDict, MyDictCacheProperties cacheProperties) {
        return new MyDictHelper(myDict, cacheProperties);
    }

}
