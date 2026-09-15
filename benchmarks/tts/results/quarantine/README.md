# Quarantined results

Records kept for provenance but excluded from every gate decision.

## p1-decision.INVALID-single-ai-listener.json

Reported `status: pass` for `kokoro-af-heart`. Rejected because:

- The sole listener was recorded as `Codex`, an AI agent with no audio input.
- Note text is byte-identical across clips from different systems and different
  Kokoro voices, so it is a function of category and system rather than audio.
- `kokoro-af-bella` and `kokoro-am-michael` produced identical score
  distributions (18 fours, 2 threes) and identical means, with within-system
  standard deviations of 0.30 to 0.58. Scores track system, not clip.
- The listener manifest leaks no system identity, so scoring that separates
  cleanly by system requires having read `key.json`. The screen was not blind.

P1 requires human listeners. This record proves the pipeline runs end to end and
nothing more.
