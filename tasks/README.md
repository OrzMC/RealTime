# Task System

Tasks are execution contracts. Every contract that has been implemented and merged lives in `tasks/completed/`;
`tasks/roadmap.md` is the ordered plan and the single place that says what is done, because a contract alone is not
proof of completion.

A contract records objective, context, dependencies, scope, constraints, acceptance criteria, tests and deliverables.
Move it from the plan into `tasks/completed/` only after its PR is merged and its evidence (test names, CI jobs,
real-server results) is written down. Code written or an open PR is not task completion.
