package io.github.canjiemo.tools.dict;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyDict 自动配置类
 * 自动装配字典帮助类和缓存配置
 *
 * @author canjiemo
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MyDictCacheProperties.class)
public class DictAutoConfiguration {

    /**
     * 创建 MyDictHelper Bean
     * 自动注入 IMyDict 实现和缓存配置
     */
    @Bean
    @ConditionalOnMissingBean
    public MyDictHelper getDictHelper(IMyDict myDict, MyDictCacheProperties cacheProperties) {
        return new MyDictHelper(myDict, cacheProperties);
    }

}
