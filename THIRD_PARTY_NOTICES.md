# Third-Party Notices

NukaCast is distributed under GNU GPLv3. The following components are included or linked by the application. This list is informational; the license files and copyright headers shipped with each component control.

- Legacy RAOP/AirPlay protocol sources derived from ShairPlay/RPiPlay-era code in `KqsMea8/AirplayServer`: LGPL-2.1-or-later and compatible per-file licenses. Modified for NukaCast JNI integration in July 2026.
- PlayFair: LGPL-3.0-or-later. See `app/src/main/cpp/legacy-airplay/lib/playfair/LICENSE.md`.
- Apple mDNSResponder is not copied into the application. NukaCast uses JmDNS instead.
- Fraunhofer FDK AAC: its own license and notices. See `app/src/main/cpp/legacy-airplay/lib/fdk-aac/NOTICE` and `MODULE_LICENSE_FRAUNHOFER`.
- axTLS crypto portions: 3-clause BSD license retained in source headers.
- Ed25519 and Curve25519 implementations: their per-file public-domain/permissive notices retained in source.
- QuickJS Android (`io.github.taoweiji.quickjs`): Apache-2.0 wrapper and upstream QuickJS license.
- ExoPlayer: Apache-2.0.
- JmDNS: Apache-2.0.
- OkHttp and Okio: Apache-2.0.
- Gson: Apache-2.0.
- NanoHTTPD: BSD-3-Clause.
- React, Vite, Tailwind CSS, lucide-react and related web packages: licenses supplied by their respective packages.

The GPLv3 text is included in `LICENSE`.
