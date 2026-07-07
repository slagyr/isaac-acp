# isaac-acp

[![CI Tests](https://github.com/slagyr/isaac-acp/actions/workflows/ci.yml/badge.svg)](https://github.com/slagyr/isaac-acp/actions/workflows/ci.yml)

ACP (Agent Communication Protocol) module for [Isaac](https://github.com/slagyr/isaac).
Provides the `isaac acp` stdio agent for ACP-compatible clients.

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
