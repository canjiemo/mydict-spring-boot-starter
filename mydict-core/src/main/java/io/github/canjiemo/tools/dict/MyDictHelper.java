package io.github.canjiemo.tools.dict;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * MyDict 字典帮助类
 * 支持 Caffeine 缓存，提升字典查询性能
 *
 * @author canjiemo
 */
public class MyDictHelper {

    private static volatile IMyDict myDict;
    private static volatile Cache<String, Optional<String>> cache;

    public MyDictHelper(IMyDict myDict, MyDictCacheProperties properties) {
        MyDictHelper.myDict = myDict;
        MyDictHelper.cache = null;

        if (properties.isEnabled()) {
            Caffeine<Object, Object> cacheBuilder = Caffeine.newBuilder()
                    .expireAfterWrite(properties.getTtl(), TimeUnit.SECONDS)
                    .maximumSize(properties.getMaxSize());

            if (properties.isRecordStats()) {
                cacheBuilder.recordStats();
            }

            cache = cacheBuilder.<String, Optional<String>>build();
        }
    }

    /**
     * 获取字典描述
     * 如果启用缓存，会自动缓存查询结果
     *
     * @param type  字典类型码
     * @param value 字典值
     * @return 字典描述
     */
    public static String getDesc(String type, Object value) {
        if (value == null) {
            return null;
        }
        String strValue = String.valueOf(value);
        if (cache == null) {
            return myDict.getDesc(type, strValue);
        }
        String cacheKey = type.length() + ":" + type + strValue;
        Optional<String> cached = cache.get(cacheKey, key -> Optional.ofNullable(myDict.getDesc(type, strValue)));
        return cached != null ? cached.orElse(null) : null;
    }

    /**
     * 清空所有缓存
     */
    public static void clearCache() {
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * 清除指定字典类型的所有缓存
     *
     * @param type 字典类型码
     */
    public static void clearCache(String type) {
        if (cache != null) {
                String prefix = type.length() + ":" + type;
            cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    /**
     * 获取缓存统计信息，需要配置 mydict.cache.record-stats=true
     *
     * @return 缓存统计信息，未启用缓存时返回 empty
     */
    public static Optional<CacheStats> getCacheStats() {
        return cache != null ? Optional.of(cache.stats()) : Optional.empty();
    }
}
