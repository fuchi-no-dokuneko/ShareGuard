package app.shareguard.core.ui

import app.shareguard.core.model.AssuranceClass

/** Centralized product wording so screens cannot accidentally overstate an assurance result. */
object ClaimLanguage {
    const val LOCAL_PROCESSING = "Processing stays on this device in the default build."
    const val MANAGED_BOUNDARY =
        "Verification applies to the exact result managed by Canonical Share. " +
            "Copies shared or exported to other apps are not monitored for later changes."
    const val TIMER_LIMIT =
        "Time since import is an approximate reference based on this device's system clock. " +
            "It is not a security guarantee and may change when the device clock changes."
    const val IMPORT_MEANING =
        "Measured from when this content entered Canonical Share, not from when the screenshot " +
            "or original content was created."
    const val DELETE_LIMIT =
        "Deletion removes the in-app result and app-addressable local management data. " +
            "It cannot remove copies already shared or exported and does not claim physical flash erasure."
    const val SHARE_CACHE_LIMIT =
        "Temporary share access and cache cleanup are best effort. Android does not tell this app " +
            "when another app has finished reading or copied the result."

    fun assuranceLabel(assuranceClass: AssuranceClass?): String = when (assuranceClass) {
        null -> "Verification pending"
        AssuranceClass.AS_0_UNVERIFIED -> "Unverified"
        AssuranceClass.AS_1_REENCODED_DERIVATIVE -> "Re-encoded derivative"
        AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT -> "Canonical text generated and verified"
        AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS -> "Image rebuilt; listed source regions retained"
        AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE -> "Fully rebuilt from reviewed content"
    }
}
