# Third-party notices for the embedded NetBird Android client

Phoenix links the Android ARM64 binding package from NetBird v0.80.0 at commit `fca64287cf51a85552a41b022013abbfdb335452`. The complete package/module graph is in `../go-dependency-graph.txt`; per-package license identifiers and source links are in `dependencies.csv`. The corresponding license and notice texts are preserved beneath `third-party/` in their package paths.

The inventory was generated for `GOOS=android GOARCH=arm64 CGO_ENABLED=0` from the exact binding package `./client/android` with `go-licenses` v1.6.0. It reports 109 package-path entries: 39 MIT, 33 Apache-2.0, 29 BSD-3-Clause, 3 BSD-2-Clause, 3 MPL-2.0, 1 ISC, and 1 CC0-1.0. Repeated license identifiers represent distinct Go packages and their notices remain in the inventory. No GPL or AGPL license was reported for this linked package graph. The separately linked NetBird root license text is `BSD-3-Clause.txt`; NetBird's server directories excluded from this Android binding are described in `../BUILDING.md`.

The report tool could not infer a source URL for `cunicu.li/go-rosenpass`; its Apache-2.0 license text is included in `third-party/cunicu.li/go-rosenpass/LICENSE`, and the pinned dependency version is recorded in the NetBird source `go.mod`.
