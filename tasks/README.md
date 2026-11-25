# Task System

Tasks are execution contracts. Every contract that has been implemented and merged lives in `tasks/completed/`;
`tasks/roadmap.md` is the ordered plan and the single place that says what is done, because a contract alone is not
proof of completion.

Start from [`TEMPLATE.md`](TEMPLATE.md). A contract records objective, context, dependencies, scope, constraints,
acceptance criteria, tests and deliverables — and, once finished, the evidence (commands, test names, CI jobs, real
server results). The surrounding process (branch naming, gates, PR shape, definition of done) is in
[`docs/development/workflow.md`](../docs/development/workflow.md).
Move it from the plan into `tasks/completed/` only after its PR is merged and its evidence (test names, CI jobs,
real-server results) is written down. Code written or an open PR is not task completion.
