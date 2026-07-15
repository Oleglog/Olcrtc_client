package io.github.oleglog.olcrtc.client.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportBuilderTest {
    @Test
    fun buildsIssueUrlWithoutCredentials() {
        val url = IssueReportBuilder.buildUrl(
            IssueReportInfo(
                appVersion = "1.0.0",
                protocol = "VLESS",
                xrayVersion = "xray-test",
                olcrtcCoreVersion = "core-test",
            ),
        )

        assertTrue(url.startsWith("https://github.com/Oleglog/Olcrtc_client/issues/new"))
        assertTrue(url.contains("VLESS"))
        assertTrue(url.contains("1.0.0"))
        assertFalse(url.contains("uuid"))
        assertFalse(url.contains("password"))
        assertFalse(url.contains("token"))
    }
}
