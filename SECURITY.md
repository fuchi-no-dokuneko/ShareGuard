# Security policy

## Reporting a vulnerability

Please open a private GitHub security advisory for this repository. Avoid a public issue until a fix is
available.

Reports should include:

- the affected app version, Android API level and device/emulator class;
- the smallest generated reproduction;
- the expected and observed block/verification states;
- whether the issue affects the Managed Artifact, an External Copy, or only UI presentation; and
- content-free diagnostic reason codes, if enabled in a diagnostic build.

Do not include real private text or images, content URIs, filesystem paths, source filenames, display
labels, provider/account details, destination apps, import/share timestamps linked to content, keys,
ciphertext/plaintext pairs from a real result, or stable device identifiers. Reproduce with generated
fixtures from `test-corpus` wherever possible.

## Supported versions

The latest tagged release is supported. Before the first tagged release, only the current `main` branch
is supported. A release is not considered published until its GitHub Actions checks and attached artifact
digests are verified.

## Scope of security claims

Canonical Share verifies processing of an exact app-managed representation. It does not provide an
anonymous transport, guarantee removal of unknown signals, control recipient copies, erase inaccessible
flash remnants, or guarantee durability beyond Android/platform storage behavior. Reports about those
documented limits are welcome as design discussion but are not automatically vulnerabilities.
