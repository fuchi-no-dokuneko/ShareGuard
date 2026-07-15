# Canonical Share privacy policy

Last updated: 2026-07-15

Canonical Share is a local-first Android app for reviewing and normalizing text or images before sharing. This policy describes the default offline edition. It is written to state the app's actual privacy boundaries without promising anonymity, forensic deletion, exact clock accuracy, or control over other apps.

## What the app processes

You can provide one primary text or image item in a session through Android sharing, the system Photo Picker, or direct text entry. The app may create temporary local data needed to process that item, such as an internal source copy, OCR views, previews, rendering intermediates, and verification information.

For content supplied by another Android app, Canonical Share copies the data into bounded app-private session storage. That internal snapshot becomes the source used by the session. This does not prove that the other app's data was unchanged before or while Android delivered it.

The default edition processes content on your device and does not require an Internet connection for its normalization pipeline. It does not upload source content, Saved Results, or encryption keys. A future network-enabled edition would require a separate product decision and an updated privacy disclosure.

## Saved Results

After a final output passes mandatory verification, Canonical Share automatically saves the verified result in app-private storage. You do not need to perform a second save before using the app's managed sharing features.

A Saved Result may include:

- the verified canonical text, rebuilt image, or derivative image you produced;
- a display label and output type;
- the applicable assurance class and a concise rationale;
- artifact format, revision, integrity, and verification information;
- the time the content was accepted into the app and the time saving completed;
- storage size, preview, favourite, migration, and lifecycle information; and
- the minimum internal references and digests needed to display, revalidate, share, export, migrate, and delete the result.

Saved Result history is not intended to retain the original source workspace. The app does not keep source filenames, source content URIs, OCR working views, source crops, clipboard alternatives, temporary render files, hidden change-ledger before-values, session paths, share-target history, recipient information, or removed source metadata as Saved Result management data.

Clearing a processing session removes or schedules cleanup of transient session data but does not delete a successfully committed Saved Result. Saved Results remain on the device until you delete them, clear the app's data, or uninstall the app, subject to Android and device behavior.

## Time since import

Canonical Share records an Import Anchor after content has been safely accepted into an internal session representation. The app uses a monotonic clock while that reference remains available during the same device boot and ordinary Android date and time to restore the display later.

**Time since import is an approximate reference based on your device's system clock. It is not a security guarantee and may change when the device clock changes.** It measures from when content entered Canonical Share, not necessarily when a screenshot was captured or original content was created. Reboot, manual clock changes, network-time adjustment, time-zone changes, and daylight-saving changes can affect its accuracy. The app prevents a negative display and does not block sharing solely because clock confidence is reduced.

The timer and import date are app interface information. They are not intentionally inserted into exported text, image pixels, image metadata, filenames, outgoing share text, QR codes, or watermarks. Waiting longer does not guarantee anonymity or defeat timing correlation.

If you choose a personal waiting target, it stays local, changes presentation only, and does not increase the result's verification or assurance. You can still share before the target.

## Sharing and exporting

Canonical Share uses the Android system Sharesheet. Where practical, it shares the managed verified artifact through a read-only content URI with temporary read permission. Some receiving apps or Android behaviors may require a temporary app-private share copy. Such cache data uses a conservative expiry and best-effort, retryable cleanup.

Canonical Share cannot know exactly when another app opens or finishes reading shared data, creates its own copy, completes its operation, or no longer needs permission. A chooser callback is not proof that the recipient consumed or deleted the content.

When you share, export, copy, or screenshot a result, that External Copy leaves Canonical Share's management boundary. The verified status applies to the copy generated at that time. Canonical Share does not monitor later edits, copies, destinations, or recipients and cannot delete an External Copy. To receive a new assurance assessment, an external item must be imported and processed again.

The app does not permanently record the selected destination, recipient, exact receiver-completion status, or exact share-launch time linked to a result. It may remember only that an app-created external export could exist so deletion screens can warn you accurately.

An optional short local delay may be used before provider-snapshot acceptance or before opening the Sharesheet. Its exact duration is not content metadata, it requires no network, and it is not provider attestation, anonymity protection, or proof against correlation.

## Deleting data

You can delete an individual Saved Result, select multiple results for deletion, or use the delete-all control. Deletion targets all data for those results that Canonical Share can address, including managed artifacts, metadata, previews, search entries, verification summaries, display labels, timing data, associated share-cache files, per-result keys where used, and pending migration or recovery references.

If a deletion attempt is incomplete, the item immediately stops appearing as a normal shareable result, verified sharing remains blocked, and the app can retry safely. Error information does not include your content or sensitive internal paths.

Deletion is logical removal from storage controlled and addressable by the app. Canonical Share does not claim to overwrite every physical flash cell or control flash wear levelling, storage-controller remapping, inaccessible remnants, or forensic recovery. Deleting an in-app result also cannot delete copies that you previously exported, shared, copied, or screenshotted.

## Storage protection and backup

Saved Results, transient source copies, previews, and sharing caches use separate app-private storage areas. The app does not automatically place Saved Results in shared media collections, Downloads, or public external storage. An external copy is created only when you choose an export or sharing action that requires one.

Where Android and the device support the selected design, sensitive Saved Result data uses encryption with keys protected by Android platform key facilities. Availability and protection depend on device capabilities. Canonical Share does not use one hard-coded key across installations, derive storage keys from a user identifier, place plaintext keys beside encrypted files, or treat unreadable ciphertext as a valid artifact.

Automatic cloud backup and cloud synchronization of Saved Results are disabled in the default edition. Cross-device backup would require a separate design covering consent, encryption, account correlation, restore integrity, deletion, and timing metadata, together with an updated privacy disclosure.

## Previews, screenshots, and Android system surfaces

You can hide Saved Result content previews without deleting the underlying result. A generic type icon may be shown instead. Previews are derived from the final saved artifact rather than the transient source and are not proof that the stored artifact still passes integrity checks.

Depending on app settings, Android version, device behavior, and product configuration, Saved Result screens may be capturable in screenshots or visible in the recent-apps interface. A screenshot is an External Copy outside app management. Notifications do not include saved content by default.

## Diagnostics and logs

Canonical Share does not put Saved Result text, image bytes, OCR text, filenames, content URIs, display labels, content-linked Import Anchors, artifact-linked share times, destinations, recipients, waiting-target values, encryption keys, or sensitive paths into normal diagnostic traces.

Debug or explicitly approved diagnostic builds may record content-free events such as a session-local event identifier, block/version, execution phase, revision, outcome class, and reason code. Persistent diagnostic tracing is disabled by default in production. Temporary in-memory troubleshooting traces are cleared with the session. Content-free aggregate analytics would require separate approval and disclosure.

Crash reporting and Android system diagnostics may be controlled partly by your device, operating system, app store, or device administrator. Those external services are outside Canonical Share's managed artifact boundary.

## Permissions

The default edition uses narrowly scoped Android mechanisms such as temporary content-URI access, the system Photo Picker, app-private storage, FileProvider-style sharing, and temporary read grants. It does not require broad photo-library access or an Internet permission for the local pipeline. Android and the source or receiving app may keep their own records under their respective privacy policies.

## Your controls

Within the app, you can:

- inspect Saved Results and their verification state;
- share or export a selected copy;
- rename or favourite a result without changing its artifact bytes;
- hide content previews;
- delete one, several, or all Saved Results;
- clear transient session and share-cache data; and
- use an app-lock or device-authentication option where supported.

No display, naming, timing, waiting-target, or organization setting can increase a result's assurance.

## Changes to this policy

This policy must be updated before enabling cloud backup, synchronization, network processing, content analytics, or another feature that materially changes the data practices described above.
