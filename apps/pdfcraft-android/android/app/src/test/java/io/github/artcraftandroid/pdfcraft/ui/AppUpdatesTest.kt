package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.updates.*
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun releaseNotesFollowLanguageWithoutMixingTranslations() {
        val body = "Intro\n<!-- pdfcraft:notes:ar -->\n- تحسين العربية\n<!-- /pdfcraft:notes -->\n" +
            "<!-- pdfcraft:notes:en -->\n- Better Arabic text\n<!-- /pdfcraft:notes -->"
        assertEquals("• تحسين العربية", localizedReleaseNotes(body, "ar"))
        assertEquals("• Better Arabic text", localizedReleaseNotes(body, "en"))
        assertEquals("• Better Arabic text", localizedReleaseNotes(body, "es"))
        assertFalse(localizedReleaseNotes(body, "ar").contains("Intro"))
    }

    @Test fun oldNotesAndMissingTranslationsHavePredictableFallbacks() {
        assertEquals("تحسين العربية", localizedReleaseNotes("تحسين العربية", "ar"))
        assertEquals("", localizedReleaseNotes("تحسين العربية", "en"))
        assertEquals("Fixed rendering", localizedReleaseNotes("Fixed rendering", "en"))
        assertEquals("", localizedReleaseNotes("Fixed rendering", "ar"))
        assertEquals("", localizedReleaseNotes("", "ar"))
        assertEquals("Title\n• Safe label", localizedReleaseNotes("## Title\n- **[Safe label](https://example.org)**<img src=x>", "en"))
    }

    @Test fun releaseDetailsMatchTheSelectedApkAndSurviveCache() {
        val r = release().copy(notes = "Notes", publishedAt = "2026-10-28T11:21:01Z",
            assets = listOf(asset().copy(size = 48_767_328)))
        val update = find(releases = listOf(r))!!
        assertEquals(r.notes, update.notes)
        assertEquals(r.publishedAt, update.publishedAt)
        assertEquals(48_767_328L, update.size)
        assertEquals(update, cachedAppUpdate("0.1.0", update.version, update.pageUrl, update.downloadUrl, update.sha256,
            update.notes, update.publishedAt, update.size))
        assertNull(cachedAppUpdate("0.2.0", update.version, update.pageUrl, update.downloadUrl, update.sha256,
            update.notes, update.publishedAt, update.size))
    }

    private val repo = "https://github.com/hussain-hz/pdfcraft-android/releases"
    private fun asset(name: String = "PdfCraft-arm64.apk", url: String = "$repo/download/v0.2.0/$name") = UpdateAsset(name, url)
    private fun release(tag: String = "v0.2.0", preview: Boolean = false, draft: Boolean = false, assets: List<UpdateAsset> = listOf(asset())) =
        AppRelease(tag, "$repo/tag/$tag", preview, draft, assets)
    private fun find(installed: String = "0.1.0-beta.14", releases: List<AppRelease>, abis: List<String> = listOf("arm64-v8a")) =
        selectAppUpdate(installed, abis, releases)

    @Test fun semverDoesNotCompareVersionNumbersAsText() {
        assertTrue(AppVersion.parse("0.1.0-beta.11")!! > AppVersion.parse("0.1.0-beta.2")!!)
        assertTrue(AppVersion.parse("v0.1.0")!! > AppVersion.parse("preview-0.1.0-beta.99")!!)
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertEquals(0, AppVersion.parse("1.0.0+build.4")!!.compareTo(AppVersion.parse("1.0.0+build.8")!!))
        assertNull(AppVersion.parse("untagged-random"))
        assertNull(AppVersion.parse("1.0.0-beta.01"))
    }

    @Test fun betaInstallStillOnlyFindsStableReleases() {
        val update = find(releases = listOf(release("v0.9.0-beta.1", preview = true), release("v0.1.0")))!!
        assertEquals("0.1.0", update.version)
    }

    @Test fun draftAndMislabeledPreviewsAreNeverOffered() {
        assertNull(find(releases = listOf(release("v9.0.0", draft = true), release("v8.0.0", preview = true), release("v7.0.0-beta.1"), release("preview-6.0.0"))))
    }

    @Test fun stableInstallDoesNotOfferBetaOrDowngrades() {
        assertNull(find("0.2.0", listOf(release("v0.1.0"), release("v0.3.0-beta.1", preview = true), release("v0.2.0"))))
        assertNull(find("0.3.0-beta.1", listOf(release("v0.2.0"))))
    }

    @Test fun selectsHighestStableVersionInsteadOfPublicationOrder() {
        assertEquals("0.10.0", find(releases = listOf(release("v0.9.0"), release("v0.10.0"), release("v0.2.0")))!!.version)
    }

    @Test fun updateVersionComesFromAppTagInsteadOfEngineInAssetName() {
        val r = release("v0.1.0", assets = listOf(asset("PdfCraft-0.1.0-engine-0.14.0-arm64.apk")))
        assertEquals("0.1.0", find(releases = listOf(r))!!.version)
        assertNull(find("0.1.0", listOf(r)))
    }

    @Test fun choosesPreferredSupportedAbiAndNotTheFirstAsset() {
        val arm32 = asset("PdfCraft-armeabi-v7a.apk")
        val arm64 = asset("PdfCraft-arm64.apk")
        val update = find(releases = listOf(release(assets = listOf(arm32, arm64))), abis = listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals(arm64.url, update.downloadUrl)
        assertEquals(arm32, compatibleApk(listOf(arm64, arm32), listOf("armeabi-v7a")))
    }

    @Test fun plainReleaseApkUsesRepositoryArm64Convention() {
        val plain = asset("pdfcraft-android-0.2.0.apk")
        assertEquals(plain, compatibleApk(listOf(plain), listOf("arm64-v8a")))
        assertNull(compatibleApk(listOf(plain), listOf("armeabi-v7a")))
        val universal = asset("PdfCraft-universal.apk")
        assertEquals(universal, compatibleApk(listOf(plain, universal), listOf("x86_64")))
    }

    @Test fun incompatibleReleaseShowsItsPageWithoutOfferingWrongApk() {
        val update = find(releases = listOf(release(assets = listOf(asset("PdfCraft-x86_64.apk")))))!!
        assertNull(update.downloadUrl)
        assertEquals("$repo/tag/v0.2.0", update.pageUrl)
    }

    @Test fun ignoresReleasesWithNoUsableApkAndUntrustedLinks() {
        assertNull(find(releases = listOf(release(assets = listOf(asset("test.pdf"))))))
        assertNull(find(releases = listOf(release(assets = listOf(asset(url = "https://example.org/app.apk"))))))
        assertNull(find(releases = listOf(release().copy(page = "https://example.org/release"))))
    }

    @Test fun releaseLinksStayOnTheOwnedHttpsRepository() {
        assertTrue(trustedReleaseUrl("$repo/download/v0.2.0/app.apk", true))
        for (url in listOf("http://github.com/hussain-hz/pdfcraft-android/releases/download/v1/app.apk", "https://github.com.evil.test/hussain-hz/pdfcraft-android/releases/download/v1/app.apk", "https://github.com/other/repo/releases/download/v1/app.apk", "https://user@github.com/hussain-hz/pdfcraft-android/releases/download/v1/app.apk", "$repo/download/../tag/v1", "$repo/download/v1/app.apk?next=elsewhere")) {
            assertFalse(url, trustedReleaseUrl(url, true))
        }
    }

    @Test fun cachedNoticesDisappearAfterInstallingAndNeverCacheBeta() {
        assertNotNull(cachedAppUpdate("0.1.0", "0.2.0", "$repo/tag/v0.2.0", "$repo/download/v0.2.0/app.apk", null))
        assertNull(cachedAppUpdate("0.2.0", "0.2.0", "$repo/tag/v0.2.0", null, null))
        assertNull(cachedAppUpdate("0.1.0", "0.3.0-beta.1", "$repo/tag/v0.3.0-beta.1", null, null))
        assertNull(cachedAppUpdate("0.1.0", "0.2.0", "https://example.org", null, null))
    }

    @Test fun foregroundChecksAvoidImmediateRepeatedRequests() {
        assertTrue(shouldCheckOnOpen(null, 100))
        assertFalse(shouldCheckOnOpen(100, 101))
        assertTrue(shouldCheckOnOpen(100, 60100))
        assertTrue(shouldCheckOnOpen(1000, 100))
    }

    @Test fun downloadedBytesMustMatchPublishedHashWhenPresent() {
        val file = java.io.File.createTempFile("update-hash-", ".apk")
        try {
            file.writeText("abc")
            assertTrue(verifiedDownloadHash(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            assertFalse(verifiedDownloadHash(file, "0".repeat(64)))
            assertFalse(verifiedDownloadHash(file, "invalid"))
        } finally { file.delete() }
    }

    @Test fun installationRequiresTheSamePackageAndSignerAndAHigherCode() {
        fun valid(pkg: String = "pdfcraft", code: Long = 2992, key: Set<String> = setOf("release-key"), version: String = "0.0.2") =
            compatibleUpdateIdentity("pdfcraft", 1992, setOf("release-key"), pkg, code, version, "0.0.2", key)
        assertTrue(valid())
        assertFalse(valid(pkg = "pdfcraft.debug"))
        assertFalse(valid(code = 1992))
        assertFalse(valid(key = setOf("debug-key")))
        assertFalse(valid(version = "0.0.2-beta.1"))
    }
}
