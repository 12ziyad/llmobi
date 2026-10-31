package app.llmobi.update

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import app.llmobi.download.ResumableDownload
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Android's package parser is simulated; signature/version policy and hashing are real. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppUpdatesTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var apk: File
    private lateinit var info: UpdateInfo
    private val releaseKey = Signature("1234567890abcdef")

    @Before fun setup() {
        apk = File(ctx.filesDir, "candidate.apk").apply { writeText("candidate APK fixture") }
        info = UpdateInfo(3, "1.2.0", "https://llmobi.pages.dev/releases/llmobi-1.2.0.apk",
            apk.length(), ResumableDownload.sha256(apk), "Fixes", 26)
        val pm = shadowOf(ctx.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = ctx.packageName; versionCode = 2; signatures = arrayOf(releaseKey)
        })
        archive()
    }

    private fun archive(code: Int = 3, name: String = ctx.packageName, key: Signature = releaseKey) {
        shadowOf(ctx.packageManager).setPackageArchiveInfo(apk.path, PackageInfo().apply {
            packageName = name; versionCode = code; signatures = arrayOf(key)
        })
    }

    @Test fun newerApkWithSamePackageAndSignerIsAccepted() {
        AppUpdates.verifyPackage(ctx, info, apk)
    }
    @Test fun differentSignerIsRejectedEvenWithValidHash() {
        archive(key = Signature("deadbeef"))
        assertTrue(runCatching { AppUpdates.verifyPackage(ctx, info, apk) }.isFailure)
    }
    @Test fun differentPackageAndDowngradeAreRejected() {
        archive(name = "other.application")
        assertTrue(runCatching { AppUpdates.verifyPackage(ctx, info, apk) }.isFailure)
        archive(code = 2)
        assertTrue(runCatching { AppUpdates.verifyPackage(ctx, info.copy(versionCode = 2), apk) }.isFailure)
    }
    @Test fun tamperedApkIsRejectedBeforeInstallation() {
        apk.writeText("x".repeat(info.bytes.toInt()))
        assertTrue(runCatching { AppUpdates.verifyPackage(ctx, info, apk) }.isFailure)
    }
    @Test fun completeDownloadIsNotReadyUntilPackageVerificationFinished() {
        val target = AppUpdates.file(ctx, info)
        target.parentFile!!.mkdirs()
        apk.copyTo(target)
        assertFalse(AppUpdates.isReady(ctx, info))
        File(target.path + ".verified").writeText(info.sha256)
        assertTrue(AppUpdates.isReady(ctx, info))
        target.appendText("corrupt")
        assertFalse(AppUpdates.isReady(ctx, info))
    }
}
