# UMS — agent orientation

## Planning location

<!-- coordination:start — identical across repos; update centrally, do not edit per repo -->
Active maps, tickets, specs, designs, implementation plans and inherited memory live in the owner's
private `~/repositories/portfolio-planning` (not fetchable from this repository). Do not create new
planning documents here.

- Read `~/repositories/portfolio-planning/capabilities/work/AGENT-GUIDE.md` first: the coordination
  loop, the enforced rules, and how to escalate.
- Find work with `cd ~/repositories/portfolio-planning/capabilities/work && ./task list --ready`,
  check `./task inbox --agent <you>`, and claim with `./task claim <id> --revision N --ttl 45m`.
- Durable memory is inherited from `portfolio-planning/.ecc/memory/team/`; save with
  `ecc memory save --scope team`.
- Promote only accepted specs/ADRs here, under `docs/`; active planning stays central.
<!-- coordination:end -->
