# Task System

Tasks are execution contracts. `backlog/` means identified but not ready; `ready/` means acceptance criteria/dependencies are clear; `in-progress/` is active; `review/` awaits verification/review; `completed/` requires verified acceptance and merged change. Move finished task contracts from `in-progress/` to `completed/` after their PR is merged and evidence is recorded.

Each task defines objective, context, dependencies, scope, constraints, acceptance criteria, tests, and deliverables. Agents select only Ready tasks with completed dependencies. Code written or an open PR is not task completion.
