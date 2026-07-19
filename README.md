# 🍏 Isaac ACP 🔌

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-acp/main/isaac-acp.png" alt="isaac-acp" style="margin-right: 20px; margin-bottom: 10px;">

ACP (Agent Communication Protocol) module for [Isaac](https://github.com/slagyr/isaac).
Provides the `isaac acp` stdio agent for ACP-compatible clients.

<br>

[![CI Tests](https://github.com/slagyr/isaac-acp/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-acp/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## Installation

Declare the module in your Isaac config's `:modules` map:

```clojure
{:modules {:isaac.comm.acp {:git/url "https://github.com/slagyr/isaac-acp.git"
                            :git/sha "<sha>"}}}
```

Isaac's loader picks up the manifest and registers the `acp` subcommand.

## Development

```bash
bb spec       # Run Clojure specs
bb features   # Run Gherkin feature scenarios
bb ci         # Run both
```

Depends on [Isaac core](https://github.com/slagyr/isaac). `bb.edn` auto-detects
a sibling `../isaac` checkout when present, so local cross-repo edits don't
need a sha bump; set `ISAAC_GIT=1` to force the pinned git sha even with the
sibling present. Bump `:git/sha` in `deps.edn` and `bb.edn` when CI / fresh
clones need newer Isaac code.

To replace the removed chat launcher, point your editor or agent client directly
at `isaac acp ...`.

## License

Copyright © 2026 Micah Martin. See [LICENSE](LICENSE).
