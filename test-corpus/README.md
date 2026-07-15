# Adversarial corpus module

This module owns versioned, machine-readable adversarial fixtures and deterministic variant generators. It
does not implement detection policy and intentionally defines no detector, OCR, quality, image, timing, or
resource threshold.

The default manifest is `src/main/resources/corpus/adversarial-corpus-v1.json`. Its schema is represented by
the serializable types in `CorpusModels.kt` and loaded strictly by `CorpusLoader`. Required fields have no
deserialization defaults so missing fixture metadata fails parsing.

All checked-in fixtures are project-authored synthetic data. Their custom `LicenseRef-` expression documents
that external reuse needs project review; it does not assert a third-party or standard open-data licence that
the repository has not established. New third-party fixtures must carry their own accurate provenance,
licence expression, attribution, and review status.

The corpus includes:

- all `TC-TXT-001` through `TC-TXT-020` cases;
- all `TC-URL-001` through `TC-URL-015` cases;
- exact and review-preserving convergence families;
- the complete required privacy-canary injection and search-surface inventory;
- baseline and persistent-result mutation cases; and
- deterministic Unicode, punctuation, URL, and metadata generators.

Consumers should adapt these declarative expectations to their own public test boundary. The module does not
import unfinished pipeline model types even though the project dependency is available.
