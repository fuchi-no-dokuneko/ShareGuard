package app.shareguard.block.verify

import android.content.pm.PackageManager
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RuntimePermissionRobolectricTest {
    @Test
    fun `test harness permissions are not misreported as production app evidence`() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val packageInfo = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val testHarnessPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        assertThat(application.packageName).endsWith(".test")
        assertThat(testHarnessPermissions).contains("android.permission.INTERNET")

        // Robolectric's generated unit-test APK requires network permissions for its own harness.
        // Production permissions are collected from the installed app package by the app adapter;
        // this fixture models that separate evidence surface instead of laundering test permissions.
        val productionPermissions = emptySet<String>()

        val fixture = VerificationFixtures.text()
        val providers = fixture.providers.copy(
            runtimePrivacyInspector = RuntimePrivacyInspector {
                ProviderResult.Completed(
                    RuntimePrivacyInspection(
                        networkEvidenceCaptured = true,
                        networkAttemptCount = 0,
                        onDemandModelDownloadCount = 0,
                        declaredPermissionNames = productionPermissions,
                        broadStoragePermissionPresent = productionPermissions.any {
                            it == "android.permission.MANAGE_EXTERNAL_STORAGE" ||
                                it == "android.permission.READ_EXTERNAL_STORAGE" ||
                                it == "android.permission.WRITE_EXTERNAL_STORAGE"
                        },
                        appPrivateArtifactRoot = true,
                        cleanupCompleted = true,
                        outgoingMimeMatchesArtifact = true,
                        outgoingDigestMatchesArtifact = true,
                        outgoingContentUriAppScoped = true,
                        temporaryReadGrantLeastPrivilege = true,
                    ),
                )
            },
        )

        val outcome = FinalVerificationCoordinator().verify(fixture.request, providers)

        assertThat(outcome.report.results.single { it.type == VerificationType.NO_NETWORK_RUNTIME }.status)
            .isEqualTo(VerificationStatus.PASS)
        assertThat(outcome.report.assuranceClass).isEqualTo(AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT)
    }
}
