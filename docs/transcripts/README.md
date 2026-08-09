# Transcripts

The brief offered to read prompts and agent transcripts. This is that record.

## What is here

[`prompts/`](./prompts/) — **every prompt I typed, in order, across 17 sessions.** Verbatim, including
the ones that were wrong and got corrected a message later. Tool calls, file contents and agent
output are omitted; so are messages passed between parallel agents, because I did not type those.

Read alongside `git log`: the commits show what was produced, these show what was asked for.

## How to read it

Two things are worth knowing before the prompt counts mislead you.

**The specification was doing most of the instructing.** 82 prompts produced roughly 8,000 lines of
specification and 90-odd Java files. That ratio is the method, not an omission: work went into
`.specs/` first, and implementation sessions were then pointed at a document rather than steered
message by message. Sessions 9–11 ran three agents in parallel worktrees — database layer, API layer,
frontend — against the same spec, with four to seven prompts each.

**The interesting prompts are the short ones.** The decisions in `DECISIONS.md` mostly arrive as one
or two sentences in the middle of a session — the scope call that products get answers and only the
operator UI gets explanations, the ruling that a restriction always defeats a concession, the
instruction to defer sign-in. Those lines are where the architecture was actually set.

## What is not here

The full session transcripts — 76 MB of JSONL across 17 sessions and 117 subagent runs — are not
committed. Two reasons, both mundane: the size is disproportionate to a code submission, and the raw
files embed this machine's configuration and one live API credential picked up from an untracked
`.env` while reading it. Available on request, redacted.
