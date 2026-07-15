# Contributing

Changes must preserve the specification precedence documented in `IMPLEMENTATION_PLAN.md` and the
security claim boundary in `docs/threat-model.md`.

For a processing block change:

1. identify the normative block ID/version and affected threat channels;
2. keep input/output contracts typed and the sequence acyclic;
3. record every app-applied transformation in the change ledger;
4. route possible meaning changes through review before commit;
5. invalidate affected downstream verification after content changes;
6. add positive, negative, convergence, cancellation and canary coverage as applicable; and
7. update dependency lineage and user-visible limits.

Do not add runtime network access, analytics, source persistence, arbitrary scripts, executable plug-ins,
regex-only URL parsing, device-font fallback in high-assurance rendering, detector-derived “clean” claims,
or thresholds that are not backed by the checked-in corpus and measurement record.

Run before proposing a change:

```bash
./gradlew test lintDebug :app:assembleDebug
```

Changes to persistence must also run migration, corruption, key-loss, transaction-interruption and
deletion tests. Changes to Android integration must compile and pass managed-emulator instrumentation
tests. Use generated fixtures; never commit real sensitive content or signing material.
