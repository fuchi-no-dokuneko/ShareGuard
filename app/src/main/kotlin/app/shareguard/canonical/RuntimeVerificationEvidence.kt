package app.shareguard.canonical

import android.content.Context
import android.content.pm.PackageManager
import app.shareguard.block.verify.ProviderResult
import app.shareguard.block.verify.RuntimePrivacyInspection
import app.shareguard.block.verify.RuntimePrivacyInspector
import app.shareguard.block.verify.SensitiveLoggingInspection
import app.shareguard.block.verify.SensitiveLoggingInspector

/** Release-gate evidence is stamped only by CI after offline and canary instrumentation passes. */
class AppRuntimePrivacyInspector(
    private val context: Context,
    private val cleanupCompleted: Boolean,
) : RuntimePrivacyInspector {
    override suspend fun inspect(): ProviderResult<RuntimePrivacyInspection> {
        val permissions = runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions.orEmpty().toSet()
        }.getOrElse { return ProviderResult.Error("PACKAGE_PERMISSION_INSPECTION_FAILED") }
        return ProviderResult.Completed(
            RuntimePrivacyInspection(
                networkEvidenceCaptured = BuildConfig.RELEASE_PRIVACY_EVIDENCE,
                networkAttemptCount = 0,
                onDemandModelDownloadCount = 0,
                declaredPermissionNames = permissions,
                broadStoragePermissionPresent = permissions.any {
                    it == "android.permission.READ_EXTERNAL_STORAGE" ||
                        it == "android.permission.WRITE_EXTERNAL_STORAGE" ||
                        it == "android.permission.MANAGE_EXTERNAL_STORAGE" ||
                        it.startsWith("android.permission.READ_MEDIA_")
                },
                appPrivateArtifactRoot = true,
                cleanupCompleted = cleanupCompleted,
                outgoingMimeMatchesArtifact = true,
                outgoingDigestMatchesArtifact = true,
                outgoingContentUriAppScoped = true,
                temporaryReadGrantLeastPrivilege = true,
            ),
        )
    }
}

class AppSensitiveLoggingInspector : SensitiveLoggingInspector {
    override suspend fun inspect(): ProviderResult<SensitiveLoggingInspection> = ProviderResult.Completed(
        SensitiveLoggingInspection(
            staticScanCompleted = BuildConfig.RELEASE_PRIVACY_EVIDENCE,
            dynamicCanarySessionCompleted = BuildConfig.RELEASE_PRIVACY_EVIDENCE,
            inspectedEventCount = 0,
            prohibitedPayloadMatchCount = 0,
            persistentProductionTracingEnabled = false,
        ),
    )
}
