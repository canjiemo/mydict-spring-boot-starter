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

    /**
     * 当用户未提供 IMyDict 实现时，在应用启动时给出明确的错误提示。
     * <p>
     * 使用方法：实现 {@link IMyDict} 接口并注册为 Spring Bean，例如：
     * <pre>
     * {@code
     * @Component
     * public class MyDictImpl implements IMyDict {
     *     @Override
     *     public String getDesc(String type, String value) {
     *         // 根据 type 和 value 查询你的字典表，返回对应描述
     *         return dictService.getLabel(type, value);
     *     }
     * }
     * }
     * </pre>
     */
    @Bean
    @ConditionalOnMissingBean(IMyDict.class)
    public IMyDict missingIMyDictBean() {
        throw new IllegalStateException(
                "\n\n" +
                "╔══════════════════════════════════════════════════════════════╗\n" +
                "║              MyDict 配置缺失                                 ║\n" +
                "╠══════════════════════════════════════════════════════════════╣\n" +
                "║  未找到 IMyDict 接口的实现 Bean。                            ║\n" +
                "║                                                              ║\n" +
                "║  请实现 IMyDict 接口并注册为 Spring Bean：                   ║\n" +
                "║                                                              ║\n" +
                "║  @Component                                                  ║\n" +
                "║  public class MyDictImpl implements IMyDict {                ║\n" +
                "║      @Override                                               ║\n" +
                "║      public String getDesc(String type, String value) {      ║\n" +
                "║          return dictService.getLabel(type, value);           ║\n" +
                "║      }                                                       ║\n" +
                "║  }                                                           ║\n" +
                "╚══════════════════════════════════════════════════════════════╝\n"
        );
    }

}
