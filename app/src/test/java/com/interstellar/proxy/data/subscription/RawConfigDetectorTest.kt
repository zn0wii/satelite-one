package com.interstellar.proxy.data.subscription

import org.junit.Test

/**
 * Raw-config format sniffing: the retained body decides whether a
 * subscription can be fed to a core verbatim.
 */
class RawConfigDetectorTest {

    @Test
    fun `clash yaml is detected`() {
        val yaml = """
            port: 7890
            proxies:
              - name: "hk"
                type: ss
                server: hk.example.com
                port: 443
                cipher: aes-256-gcm
                password: pw
            proxy-groups:
              - name: "PROXY"
                type: select
                proxies: ["hk"]
            rules:
              - MATCH,PROXY
        """.trimIndent()
        check(RawConfigDetector.detect(yaml) == RawConfigFormat.CLASH)
    }

    @Test
    fun `singbox json is detected by type field`() {
        val json = """
            {
              "outbounds": [
                {"type": "selector", "tag": "proxy", "outbounds": ["auto"]},
                {"type": "vmess", "tag": "hk", "server": "hk.example.com"}
              ]
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == RawConfigFormat.SINGBOX)
    }

    @Test
    fun `xray json is detected by protocol field`() {
        val json = """
            {
              "outbounds": [
                {"tag": "proxy", "protocol": "vless", "settings": {}},
                {"tag": "direct", "protocol": "freedom"}
              ],
              "routing": {"rules": []}
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == RawConfigFormat.XRAY)
    }

    @Test
    fun `base64-wrapped clash config is detected`() {
        val yaml = "proxies:\n  - name: a\n    type: ss\n    server: s\n    port: 1\n    cipher: x\n    password: p\n"
        val b64 = java.util.Base64.getEncoder().encodeToString(yaml.toByteArray())
        check(RawConfigDetector.detect(b64) == RawConfigFormat.CLASH)
    }

    @Test
    fun `plain uri node list is not a raw config`() {
        check(RawConfigDetector.detect("vmess://eyJ2IjoiMiIsInBzIjoibiIsImFkZCI6ImEuY29tIn0=\n") == null)
        check(RawConfigDetector.detect("ss://YWVzLTI1Ni1nY206cHdAaC5leGFtcGxlLmNvbTo0NDM#node\n") == null)
    }

    @Test
    fun `json without outbounds is not a raw config`() {
        check(RawConfigDetector.detect("""{"foo": "bar"}""") == null)
    }
}
