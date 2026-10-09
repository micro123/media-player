package io.github.micro123.mediaplayer.data.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    private fun compare(left: String, right: String) = requireNotNull(ReleaseVersion.parse(left)).compareTo(requireNotNull(ReleaseVersion.parse(right)))
    @Test fun comparesNumbersInsteadOfTextAndNeverSuggestsDowngrades() {
        assertTrue(compare("v0.15.0", "0.14.0") > 0)
        assertTrue(compare("v0.10.0", "0.9.9") > 0)
        assertTrue(compare("v1.0.0", "0.99.0") > 0)
        assertTrue(compare("0.14.0", "0.15.0") < 0)
        assertEquals(0, compare("v0.14.0", "0.14.0"))
        assertEquals(0, compare("0.14", "0.14.0"))
        assertEquals(0, compare("0.14.0+local.7", "0.14.0"))
    }
    @Test fun stableVersionsFollowPrereleasesAndPrereleaseNumbersAreNumeric() {
        assertTrue(compare("0.15.0", "0.15.0-rc.10") > 0)
        assertTrue(compare("0.15.0-rc.10", "0.15.0-rc.2") > 0)
        assertTrue(compare("0.15.0-alpha", "0.15.0-alpha.1") < 0)
        assertTrue(compare("0.15.0-1", "0.15.0-alpha") < 0)
        assertTrue(compare("0.15.0-beta", "0.15.0-alpha") > 0)
    }
    @Test fun malformedVersionsAreNotSilentlyTreatedAsNewer() {
        listOf("", "nightly", "release-1.0", "1..2", "-1.2", "1.2/3", "1.2-", "1.2+", "1.0".repeat(100)).forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
    }
    @Test fun prefersDeviceAbiInOrderWithUniversalOnlyAsAFallback() {
        fun apk(abi: String) = ReleaseApk("media-player-0.15.0-$abi.apk", "https://github.com/fixture/$abi", 1024)
        val arm = apk("arm64-v8a"); val arm32 = apk("armeabi-v7a"); val x64 = apk("x86_64"); val universal = apk("universal")
        val release = GithubRelease("v0.15.0", "测试", "", "", "", listOf(arm32, universal, x64, arm))
        assertEquals(arm, release.preferredApk(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(x64, release.preferredApk(listOf("x86_64", "x86")))
        assertEquals(arm32, release.preferredApk(listOf("armeabi-v7a")))
        assertEquals(universal, release.preferredApk(listOf("riscv64")))
        assertEquals(universal, release.preferredApk(emptyList()))
        assertNull(release.copy(apks = listOf(x64)).preferredApk(listOf("arm64-v8a")))
    }
}
