# UMS — agent orientation

## Planning location

<!-- coordination:start — identical across repos; update centrally, do not edit per repo -->
Active maps, tickets, specs, designs and implementation plans live in the owner's private
`~/repositories/portfolio-planning`. The coordination engine — registry, claims, contracts, MCP and
A2A — is `~/repositories/loom` and is public.

- Read `~/repositories/loom/capabilities/work/AGENT-GUIDE.md` first: the coordination loop, the
  enforced rules, and how to escalate.
- Find work with `cd ~/repositories/loom/capabilities/work && ./task list --ready`,
  check `./task inbox --agent <you>`, and claim with `./task claim <id> --revision N --ttl 45m`.
- Task state lives in `~/.local/state/loom/work.db`; inherited memory lives in
  `portfolio-planning/.ecc/memory/team/`.
- Promote only accepted specs/ADRs here, under `docs/`; active planning stays central.
<!-- coordination:end -->
