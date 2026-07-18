package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeConfigTest {
    @Test
    fun buildsVlessRealityVision() {
        val json = NativeConfig.xray(1080, StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = UUID,
            transport = StandardProfile.Transport.TCP,
            security = StandardProfile.Security.REALITY,
            flow = StandardProfile.VISION_FLOW,
            serverName = "www.example.com",
            fingerprint = "chrome",
            realityPublicKey = "key",
            realityShortId = "short",
        ))

        assertTrue(json.contains("\"protocol\": \"vless\""))
        assertTrue(json.contains("\"flow\": \"xtls-rprx-vision-udp443\""))
        assertTrue(json.contains("\"packetEncoding\": \"xudp\""))
        assertTrue(json.contains("\"security\": \"reality\""))
        assertTrue(json.contains("\"publicKey\": \"key\""))
        assertFalse(json.contains("\"protocol\": \"socks\",\n  \"tag\": \"proxy\""))
    }

    @Test
    fun buildsVmessWebSocketTlsAndTrojanGrpc() {
        val vmess = NativeConfig.xray(1080, StandardProfile(
            name = "VMess",
            protocol = StandardProfile.Protocol.VMESS,
            address = "example.com",
            port = 443,
            uuid = UUID,
            transport = StandardProfile.Transport.WS,
            security = StandardProfile.Security.TLS,
            webSocketPath = "/vmess",
            webSocketHost = "cdn.example.com",
        ))
        val trojan = NativeConfig.xray(1080, StandardProfile(
            name = "Trojan",
            protocol = StandardProfile.Protocol.TROJAN,
            address = "example.com",
            port = 443,
            password = "secret",
            transport = StandardProfile.Transport.GRPC,
            security = StandardProfile.Security.TLS,
            grpcServiceName = "trojan",
        ))

        assertTrue(vmess.contains("\"protocol\": \"vmess\""))
        assertTrue(vmess.contains("\"network\": \"ws\""))
        assertTrue(vmess.contains("\"allowInsecure\": false"))
        assertTrue(trojan.contains("\"protocol\": \"trojan\""))
        assertTrue(trojan.contains("\"serviceName\": \"trojan\""))
    }

    @Test
    fun escapesProfileValues() {
        val json = NativeConfig.xray(1080, StandardProfile(
            name = "Test",
            protocol = StandardProfile.Protocol.TROJAN,
            address = "host\"name",
            port = 443,
            password = "pass\\word",
            transport = StandardProfile.Transport.TCP,
            security = StandardProfile.Security.TLS,
        ))

        assertTrue(json.contains("host\\\"name"))
        assertTrue(json.contains("pass\\\\word"))
    }

    @Test
    fun buildsShadowsocksOutboundWithoutStreamSettings() {
        val json = NativeConfig.xray(1080, StandardProfile(
            name = "SS",
            protocol = StandardProfile.Protocol.SHADOWSOCKS,
            address = "ss.example.com",
            port = 8388,
            password = "secret",
            cipher = "aes-256-gcm",
        ))

        assertTrue(json.contains("\"protocol\": \"shadowsocks\""))
        assertTrue(json.contains("\"method\": \"aes-256-gcm\""))
        assertTrue(json.contains("\"password\": \"secret\""))
        assertFalse(json.contains("streamSettings"))
    }

    @Test
    fun routesConfiguredDnsThroughProxyBeforeUserRules() {
        val userRule = routingRule(
            RoutingRule.MatchType.DOMAIN,
            "blocked.example",
            RoutingRule.Action.BLOCK,
        )

        val json = NativeConfig.xray(
            socksPort = 1080,
            dns = DnsEndpoint.parse("[2001:4860:4860::8888]:5353"),
            routingRules = listOf(userRule),
        )

        assertTrue(json.contains("\"address\": \"tcp://[2001:4860:4860:0:0:0:0:8888]:5353\", \"tag\": \"dns-proxy\""))
        assertTrue(json.contains("\"protocol\": \"dns\", \"tag\": \"dns-out\""))
        assertFalse(json.contains("dns-tls-out"))
        assertFalse(json.contains("\"redirect\""))
        val dnsRule = json.indexOf("\"inboundTag\": [\"dns-proxy\"]")
        val interceptedDnsRule = json.indexOf("\"ip\": [\"${NativeConfig.VPN_DNS_ADDRESS}\"]")
        val privateDnsRule = json.indexOf("\"port\": \"853\", \"network\": \"tcp\", \"outboundTag\": \"block\"")
        val userRuleIndex = json.indexOf("full:blocked.example")
        assertTrue(dnsRule >= 0)
        assertTrue(dnsRule < userRuleIndex)
        assertTrue(interceptedDnsRule in 0..<userRuleIndex)
        assertTrue(privateDnsRule in 0..<userRuleIndex)
        assertTrue(json.contains("\"port\": \"53\", \"network\": \"tcp,udp\", \"outboundTag\": \"dns-out\""))
    }

    @Test
    fun routesLatencyTestThroughProxyBeforeUserRules() {
        val json = NativeConfig.xray(
            socksPort = 1080,
            routingRules = listOf(routingRule(
                RoutingRule.MatchType.DOMAIN,
                "www.google.com",
                RoutingRule.Action.DIRECT,
            )),
        )

        val latencyRule = json.indexOf("\"inboundTag\": [\"latency-test\"]")
        val directRule = json.indexOf("full:www.google.com")
        assertTrue(latencyRule >= 0)
        assertTrue(latencyRule < directRule)
    }

    @Test
    fun buildsRoutingRulesForAllActions() {
        val rules = listOf(
            routingRule(RoutingRule.MatchType.DOMAIN, "exact.example", RoutingRule.Action.VPN),
            routingRule(RoutingRule.MatchType.DOMAIN_SUFFIX, "example.org", RoutingRule.Action.DIRECT),
            routingRule(RoutingRule.MatchType.IP, "192.0.2.1", RoutingRule.Action.BLOCK),
            routingRule(RoutingRule.MatchType.CIDR, "2001:db8::/32", RoutingRule.Action.VPN),
        )

        val json = NativeConfig.xray(1080, routingRules = rules)

        assertTrue(json.contains("\"protocol\": \"freedom\", \"tag\": \"proxy\""))
        assertTrue(json.contains("\"protocol\": \"freedom\", \"tag\": \"direct\""))
        assertTrue(json.contains("\"protocol\": \"blackhole\", \"tag\": \"block\""))
        assertTrue(json.contains("\"domain\": [\"full:exact.example\"]"))
        assertTrue(json.contains("\"domain\": [\"domain:example.org\"]"))
        assertTrue(json.contains("\"ip\": [\"192.0.2.1\"]"))
        assertTrue(json.contains("\"ip\": [\"2001:db8:0:0:0:0:0:0/32\"]"))
        assertTrue(json.contains("\"outboundTag\": \"proxy\""))
        assertTrue(json.contains("\"outboundTag\": \"direct\""))
        assertTrue(json.contains("\"outboundTag\": \"block\""))
        assertFalse(json.contains("\"outboundTag\": \"vpn\""))
    }

    @Test
    fun ordersUserLanGeoAndDefaultRoutingRules() {
        val userRule = routingRule(
            RoutingRule.MatchType.DOMAIN,
            "blocked.example",
            RoutingRule.Action.BLOCK,
        )

        val json = NativeConfig.xray(
            socksPort = 1080,
            routingRules = listOf(userRule),
            routingPolicy = RoutingPolicy(
                preset = RoutingPolicy.Preset.RUSSIA_DIRECT,
                allowLan = true,
            ),
        )

        val user = json.indexOf("full:blocked.example")
        val lan = json.indexOf("10.0.0.0/8")
        val geoIp = json.indexOf("geoip:ru")
        val ruDomains = json.indexOf("domain:ru")
        val rfDomains = json.indexOf("domain:xn--p1ai")
        val geoSite = json.indexOf("geosite:category-ru")
        val default = json.lastIndexOf("\"network\": \"tcp,udp\"")
        assertTrue(user in 0..<lan)
        assertTrue(lan < geoIp)
        assertTrue(geoIp < ruDomains)
        assertTrue(ruDomains < rfDomains)
        assertTrue(rfDomains < geoSite)
        assertTrue(geoSite < default)
    }

    @Test
    fun allVpnOmitsLanAndRussiaGeoRules() {
        val json = NativeConfig.xray(
            socksPort = 1080,
            routingPolicy = RoutingPolicy(RoutingPolicy.Preset.ALL_VPN),
        )

        assertFalse(json.contains("10.0.0.0/8"))
        assertFalse(json.contains("geoip:ru"))
        assertFalse(json.contains("geosite:category-ru"))
        assertTrue(json.contains("\"network\": \"tcp,udp\""))
    }

    private fun routingRule(
        type: RoutingRule.MatchType,
        value: String,
        action: RoutingRule.Action,
    ) = RoutingRule.create(matchType = type, value = value, action = action)

    private companion object {
        const val UUID = "00000000-0000-0000-0000-000000000001"
    }
}
