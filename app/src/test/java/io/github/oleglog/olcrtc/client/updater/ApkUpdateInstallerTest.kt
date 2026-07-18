package io.github.oleglog.olcrtc.client.updater

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ApkUpdateInstallerTest {
    @Test
    fun calculatesSha256ForBytesAndFiles() {
        val bytes = "abc".encodeToByteArray()
        val file = Files.createTempFile("apk", ".bin").toFile().apply { writeBytes(bytes) }

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkUpdateInstaller.sha256(bytes))
        assertEquals(ApkUpdateInstaller.sha256(bytes), ApkUpdateInstaller.sha256(file))
    }

    @Test
    fun installClickEitherRequestsPermissionOrStartsDownload() {
        assertEquals(UpdateInstallAction.REQUEST_PERMISSION, updateInstallAction(false))
        assertEquals(UpdateInstallAction.DOWNLOAD, updateInstallAction(true))
    }
}
