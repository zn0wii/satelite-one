package com.interstellar.proxy.data.net

import org.junit.Test

/**
 * FlClash-style Content-Disposition parsing — CN panels mostly send the
 * RFC 5987 star form with a percent-encoded UTF-8 name.
 */
class SubscriptionFetcherTest {

    @Test
    fun `rfc5987 filename-star decodes percent-encoded utf8`() {
        val header = "attachment; filename*=UTF-8''%E8%89%AF%E5%BF%83%E4%BA%91"
        check(SubscriptionFetcher.parseDispositionName(header) == "良心云")
    }

    @Test
    fun `plain filename decodes and strips extension`() {
        check(SubscriptionFetcher.parseDispositionName("attachment; filename=\"my-sub.yaml\"") == "my-sub")
        check(
            SubscriptionFetcher.parseDispositionName(
                "attachment; filename=\"%E6%9C%BA%E5%9C%BA%E5%90%8D.txt\"",
            ) == "机场名",
        )
    }

    @Test
    fun `star form wins over plain filename`() {
        val header = "attachment; filename=\"fallback.yaml\"; filename*=UTF-8''%E6%B5%8B%E8%AF%95"
        check(SubscriptionFetcher.parseDispositionName(header) == "测试")
    }

    @Test
    fun `literal plus survives decoding`() {
        check(SubscriptionFetcher.parseDispositionName("attachment; filename=\"a+b.yaml\"") == "a+b")
    }

    @Test
    fun `garbage headers yield null`() {
        check(SubscriptionFetcher.parseDispositionName(null) == null)
        check(SubscriptionFetcher.parseDispositionName("") == null)
        check(SubscriptionFetcher.parseDispositionName("inline") == null)
        // empty after cleaning
        check(SubscriptionFetcher.parseDispositionName("attachment; filename=\".yaml\"") == null)
    }
}
