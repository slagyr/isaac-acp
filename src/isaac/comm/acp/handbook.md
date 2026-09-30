<!--
Lint convention (isaac.comm.acp.handbook-chapter-spec, isaac-vwa6, mirroring
isaac.foundation's own and isaac-gmail's isaac-g8k2): a backtick
`config:<dotted.path>` reference (no angle-bracket placeholder inside the
path) is checked against the composed config schema, and the word right
after `isaac ` in `isaac <command>` is checked against the registered
top-level CLI commands. Keep both literal and real when you edit this file —
the lint fails the build once either drifts from what Isaac actually
exposes. `<placeholder>` shapes (e.g. `config:<dotted.path>` itself, or
`<module-id>#<slug>`) are intentionally skipped.
-->

# isaac.comm.acp — ACP comm

You are a crew running inside Isaac. This chapter covers **isaac-acp**: the
`isaac acp` stdio agent surface that speaks the Agent Client Protocol (ACP)
so editors and ACP-aware clients (Zed, IntelliJ, Toad, and similar) can drive
Isaac as their agent. Read `isaac.foundation` first if you haven't (config
mechanics, `handbook__configure`, hot reload); read `isaac.agent` for crews,
sessions, tools, and the turn/compaction machinery ACP rides on top of — this
chapter only covers what's specific to the ACP surface itself.

isaac-acp declares **no config schema of its own**. Every knob you'd reach
for here is either a CLI flag on `isaac acp` or a config path owned by
`isaac.agent` (crews, sessions, frequencies) — this chapter names those and
points at `isaac.agent` rather than re-documenting them.

## The ACP comm

**What it is.** `isaac acp` runs Isaac as a long-lived stdio process: it
reads newline-delimited JSON-RPC from stdin, writes JSON-RPC responses and
`session/update` notifications to stdout, and loops until stdin closes (a
clean EOF exits 0). Nothing about it is a `comms` table entry — unlike
Discord or Gmail, an ACP client doesn't get configured in advance; the
editor or client itself launches `isaac acp [options]` as a subprocess and
speaks the protocol over that process's pipes. `initialize` returns
`protocolVersion`, `agentInfo` (`name: "isaac"`, plus the resolved crew's
`model`/`provider` once known), and `agentCapabilities` (`loadSession:
true`, `promptCapabilities.text: true` — ACP text prompts only; no image or
audio content today `[verify]`).

**How to change it.** There's no config path for any of this — it's CLI-only
flags read by the editor's own ACP client configuration, not something a
turn changes from inside a session. `isaac help acp` lists every flag.

**How to verify.** `isaac help acp` shows the usage line. Piping a single
`initialize` request into `isaac acp` and reading stdout back confirms the
process speaks JSON-RPC; `isaac acp --verbose` echoes each inbound method
name to stderr, which is the fastest way to confirm an editor is actually
sending what you expect.

### Troubleshooting

- **The client hangs waiting for a response.** Confirm you're writing valid,
  newline-terminated JSON-RPC to stdin — the read loop blocks on
  `readLine`; a message missing its trailing newline is never dispatched.
- **`agentInfo` never includes a model or provider.** That only appears once
  a crew resolves cleanly for the process's effective config; an unresolved
  or missing crew leaves those fields absent rather than erroring.
- **Nothing happens and the process exits immediately.** Empty stdin (EOF
  with no input at all) is a clean, successful exit (code 0) — not a hang or
  a crash.

## Session selection and per-turn overrides

**What it is.** `isaac acp` attaches to exactly **one** resolved session for
the life of the process — there's no equivalent of hail's `--reach` to
address other sessions mid-run. Selection uses the same shared
session-frequencies flags the `prompt` command uses: `--session`/`-s`
(exact id), `--crew`/`-c`, `--session-tag`/`--tag` (repeatable, ANDed),
`--resume`/`-R` (most recent session overall), and `--prefer` (`recent` or
`oldest` among ties). `--create` (`never`, `if-missing`, `always`) controls
whether a miss creates a session; the built-in default is `if-missing`.
`--session` combined with any other selection flag, or `--resume` combined
with any selection flag, is a usage error reported before anything runs.

A **blank** `isaac acp` (no selection flags at all) resolves through the
same defaults an unattended hail turn would: `config:defaults.frequencies.crew`
supplies the crew when none is named, and
`config:defaults.frequencies.create` (default `:if-missing`) decides whether a miss
creates a session — an explicit `--create` on the command line only wins
when the config default is *not* `:always` (isaac-asik decision, 2026-09-27).
With nothing configured and no flags at all, ACP fails outright rather than
guessing — see Troubleshooting.

`session/new` (the ACP method, not the CLI) always returns this one resolved
session, minting a fresh id (`acp-<yyyy-MM-dd-HHmm>-<4 chars>`) when nothing
named one explicitly. A second `session/new` call on an already-attached
process returns that same session again rather than opening another one. Per
turn, `--with-model`/`-M`/`--model` overrides the model, `--with-crew`
overrides the crew, and `--with-effort`/`--with-context-mode` override
those — all from `isaac.agent`'s shared override mechanism; see that
chapter's Providers, models, and effort section.

**How to change it.** These are all launch-time CLI flags, not config paths:

```
isaac acp --crew cordelia --resume
isaac acp --session harbor --with-model quantum-anvil
```

The one config surface behind the blank-invocation fallback:

```
config set defaults.frequencies.crew cordelia
config set defaults.frequencies.create if-missing
```

**How to verify.** The `session/new` (or first `session/prompt`) response's
`sessionId` names the session actually attached; `isaac sessions show <id>`
confirms its crew and recency.

### Troubleshooting

- **`isaac acp --session <id>` fails with "session not found."** An explicit
  `--session` must already exist — ACP never silently creates one for a
  named-but-missing session; drop `--session` (or add `--create always`
  behavior via config) if you meant to start fresh.
- **A blank `isaac acp` exits with "no session selected."** Nothing on the
  command line and nothing under `config:defaults.frequencies.crew` gave it
  a crew or session to resolve — set that path, or launch
  with an explicit `--crew`/`--session`.
- **`--session foo --crew bar` is rejected.** `--session` is exclusive with
  every other selection flag by design — an explicit id is a complete
  answer on its own.
- **A configured `defaults.frequencies.create always` won't downgrade to
  `if-missing` no matter what flag you pass.** That's the isaac-asik rule —
  a configured `:always` outranks an explicit `--create`; only a config
  change (or a different configured value) can undo it.

## Episodes under an ACP session

**What it is.** Whatever the ACP `sessionId` is, it **never changes** —
episodes rotate underneath it, invisibly to the client. For a crew whose
`session-policy` (see `isaac.agent`) is `:episodes` rather than the default
`:chronicle`, the first prompt on that session id opens an episode with
recall-at-open; later prompts within the episode's warm window append to
the same open episode; the client never sees an episode id or a rotation
event — `session/load` and transcript replay behave identically either way
from the ACP side. Chronicle crews (the default) are byte-identical to
today's behavior: `session/new` creates the named session directly, no
episode machinery involved. `--crew` on an episodes crew resumes that
crew's most recent session the same way a chronicle crew's `--resume`/
`--crew` would. Full episode mechanics (sealing, scenes, recall) belong to
`isaac.session.episodes` — read that chapter for how episodes actually
close and get searched.

**How to change it.** `session-policy` is set per crew, not per ACP
invocation:

```
config set crew.cordelia.session-policy episodes
```

**How to verify.** Prompt an episodes crew's session twice in a row through
ACP and confirm both `session/prompt` calls return the same `sessionId` with
no visible change in behavior; `isaac.session.episodes`'s own topic covers
inspecting the episode record itself.

### Troubleshooting

- **An episodes crew's session seems to "forget" things between turns.**
  That's the episode's warm/cold boundary, not an ACP bug — see
  `isaac.session.episodes` for the TTL and what recall-at-open actually
  injects.
- **`--crew` on an episodes crew doesn't resume what you expected.** It
  resumes that crew's most recent *session id*, not a specific open
  episode — episodes within that session id rotate on their own schedule.

## Streaming and message replay

**What it is.** As a turn generates text, ACP forwards each provider chunk
as its own `session/update` notification with `sessionUpdate:
"agent_message_chunk"` — front-ends render these incrementally rather than
waiting for one final blob. Operational narration (compaction status; see
Compaction notifications, below) instead uses `"agent_thought_chunk"`, kept
distinct so a client never mistakes "compacting..." for part of the actual
reply. `session/load` (resuming a session already on disk) replays its
transcript as the same notification shapes: each stored user message as
`"user_message_chunk"`, each assistant message as `"agent_message_chunk"`,
each stored tool call/result pair as a completed `"tool_call"`/
`"tool_call_update"` pair, and a stored compaction record's summary as an
`"agent_message_chunk"` — all in original order, so re-opening a session in
an editor looks the same as it would live. When a compaction offset has
trimmed the head of the transcript, replay starts from the active window
(the compaction summary, then whatever's still live) rather than the full
history from the beginning.

**How to change it.** Nothing here is configurable — it's the wire format
of an already-configured turn. The only upstream lever is the transcript
itself, i.e. `isaac.agent`'s `history-retention` and compaction settings.

**How to verify.** Issue `session/load` against a session with existing
transcript history and confirm the notification stream carries the
messages/tool calls in order before any response arrives.

### Troubleshooting

- **A resumed session skips its earliest messages.** Check whether that
  session has a compaction offset (`isaac sessions show <id>`) — replay
  intentionally starts at the active head, not the raw beginning, once
  compaction has trimmed it.
- **Chunk boundaries look odd (extra or missing spaces).** Chunks are
  forwarded exactly as the provider emitted them; reassemble by
  concatenation on the client side rather than assuming whitespace at
  chunk edges is meaningful.

## Tool call display

**What it is.** A tool call in flight becomes a `"tool_call"` notification
(`status: "pending"`) carrying `toolCallId`, `title` (`"<tool>: <summary>"`,
where the summary is the tool's command/file-path argument when present),
`kind` (`"read"`, `"edit"`, `"execute"`, or `"other"` — derived from the
tool's name, not user-configurable), `rawInput` (the raw arguments map),
and a `content` block with the arguments rendered as text so a thin client
can expand the call before it finishes. Completion follows as a
`"tool_call_update"` (`status: "completed"`) repeating the same
title/kind/rawInput plus `rawOutput` and a text `content` block built from
the tool's result. A cancelled-mid-flight call instead gets a
`"tool_call_update"` with `status: "cancelled"` (see Cancellation, below).
Which tools are actually callable, and directory grants, are entirely
`isaac.agent`'s allow/deny cascade — this module only renders whatever tool
calls the turn actually makes.

**How to change it.** Nothing here is configurable from the ACP side — grant
or restrict the underlying tools per crew as usual:

```
config set crew.cordelia.tools.allow '[:exec/run :fs/read]'
```

**How to verify.** Prompt a crew with a tool it's allowed to call and watch
for the `pending` → `completed` (or `cancelled`) notification pair, in that
order, before the turn's final response.

### Troubleshooting

- **A tool call notification never resolves to `completed`.** Check whether
  the turn was cancelled mid-call (see Cancellation) — a cancelled call
  reports `status: "cancelled"`, not a stalled `pending`.
- **`title`/`kind` look generic (`"other"`, just the tool name).** `kind` is
  derived from a small fixed set of known tool-name patterns
  (read/edit/write/exec); anything else — including most non-filesystem,
  non-exec tools — reports `"other"`, which is expected, not a bug.

## Cancellation

**What it is.** `session/cancel` is a notification (no response expected)
that triggers Isaac's bridge-level cancellation for that session's
in-flight turn. The turn's own pending `session/prompt` response then
resolves with `result.stopReason: "cancelled"` rather than `"end_turn"`. If
a tool call was mid-flight when the cancel landed, its `tool_call_update`
arrives with `status: "cancelled"` (see Tool call display, above) so the
client can clear its pending indicator instead of leaving it stuck. Every
`session/cancel` arrival is logged at info (`:acp/session-cancel-received`)
regardless of whether a turn was actually running. A cancelled session
remains fully usable afterward — the next `session/prompt` on the same
session id runs a normal, independent turn.

**How to change it.** Nothing here is configurable; it's protocol behavior
triggered by the client sending the notification.

**How to verify.** Start a long-running prompt (a slow tool call, or a
delayed provider response), send `session/cancel` for that `sessionId`, and
confirm the original request's response reports `stopReason: "cancelled"`.

### Troubleshooting

- **`session/cancel` seems to do nothing.** It's a notification, not a
  request — there's no response to inspect directly; check the pending
  `session/prompt` response's `stopReason` instead, and confirm the log
  shows `:acp/session-cancel-received` for that session id.
- **A session looks unusable after a cancel.** It isn't — a fresh
  `session/prompt` on the same id should run normally; if it doesn't,
  that's a different failure (see `isaac.agent`'s Turns and the tool loop
  for suspension vs. cancellation).

## Slash commands

**What it is.** After session creation, ACP advertises every available
slash command via an `available_commands_update` notification: built-ins
(`status`, `model`, `crew`, `cwd`, `effort`, in that order) first, then
config-defined prompt-template commands and any module-contributed ones,
each with a `name`, `description`, and — when the command declares
`params` — an `input.hint` naming its first parameter. A user's `/command
...` text is sent to Isaac as an ordinary prompt; the bridge (see
`isaac.agent`) intercepts it before the model sees it and it is **not**
added to the session transcript. Most slash commands reply as a normal
`agent_message_chunk`; `/status` is the one exception — it emits a
structured `chat/status` notification (`crew`, `model`, `provider`,
`session-key`, and more) instead of markdown text, so CLI and
markdown-capable clients can each render it their own way.

**How to change it.** Which commands exist is `isaac.agent`'s domain
(built-ins plus `prompts/commands/*.md` templates); ACP only advertises
whatever that module reports.

**How to verify.** Create a session and check the `available_commands_update`
notification's `availableCommands` list; send `/status` and check for a
`chat/status` notification rather than a text chunk.

### Troubleshooting

- **A slash command doesn't show up in the advertised list.** Confirm it's
  actually registered — a config-defined command template lives under
  `prompts/commands/` relative to the working directory ACP was launched
  from (`--cwd`/the process cwd), not an arbitrary path.
- **`/status` renders as plain text in your client.** Your client is likely
  falling back to something other than the dedicated `chat/status`
  notification — that data is intentionally separate from `session/update`
  message chunks.

## Errors, exceptions, and compaction status

**What it is.** A provider error (quota exceeded, connection refused, an
unhandled exception during the turn) is **never** surfaced as a JSON-RPC
error and never uses a made-up `"error"` `stopReason` — the ACP spec has no
such stop reason, and clients that see one commonly show a generic
"Internal error" instead of anything useful. Instead, the readable message
is sent as an ordinary `agent_message_chunk` and the turn still ends with
`stopReason: "end_turn"`. The same applies to an unknown/stale crew on a
session (the operator renamed or removed a crew a session still points at):
one guidance message ("unknown crew on session `<id>`: `<crew>` — send
`/crew <name> to change crew`") goes out the same way. Separately, when a
turn triggers compaction mid-flight, the client gets an
`agent_thought_chunk` narrating it — `"compacting..."`,
`"compacted."`, or `"compaction failed: <reason>"` / `"compaction
disabled: <reason>"` — distinct from the reply itself (see Streaming and
message replay, above) so a client can show "thinking/working" UI without
mistaking it for content.

**How to change it.** Nothing here is configurable from ACP; the underlying
provider-error and compaction behavior is `isaac.agent`'s (model-fallback,
compaction strategy/threshold).

**How to verify.** Trigger a provider error (an invalid/unreachable model
config) or a tight `context-window` and confirm the client receives
readable `agent_message_chunk` text plus a normal `end_turn` response,
never a raw JSON-RPC error object.

### Troubleshooting

- **A client shows "Internal error" instead of a real message.** That
  suggests something upstream of this module (a proxy, an older client
  build) is misinterpreting the response — a compliant ACP client should
  never need to fall back to a generic error for anything this module
  sends, since errors are always plain `agent_message_chunk` text with
  `end_turn`.
- **You see "unknown crew on session" repeatedly.** The session is pinned
  to a crew id that no longer exists in config — either restore that crew
  or send `/crew <name>` (see Slash commands) to point the session at a
  live one.
- **Compaction narration (`"compacting..."`) never appears even though the
  session clearly compacted.** Confirm the client is rendering
  `agent_thought_chunk` updates at all — some minimal clients only render
  `agent_message_chunk` and silently drop thought chunks; that's a client
  choice, not a sign compaction didn't run.
