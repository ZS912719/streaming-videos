# Future general solver: evidence and next experiment

This mission intentionally did not implement a new adaptive solver. The frozen
promotion pipeline and selected challenge instance answer different questions.
The promotion pilot is selection data; the selected competitive instance is
deliberately favorable; neither is an unbiased universal-superiority test.

## Retain a small construction portfolio

Keep canonical zero regret, static regret w=1 and dynamic feasible-alternative
regret w=1. Legacy Greedy remains a reproducibility reference, but it frequently
duplicates zero-regret outcomes in the new synthetic tests. Additional weights
should earn their place through nonredundant output and held-out performance,
not through a larger list of algorithm names.

Dynamic plus repair won the promotion pilot and the selected challenge. However,
static/zero/reverse references won every held-out mixed-family case. In the
selected challenge dynamic construction itself scored below static before repair.
Consequently, initial construction score alone is a poor elimination criterion:
give promising diverse starts a short repair trial before allocating more work.

## Repair priorities and resource control

Insertion/one-for-one LS is cheap on small sparse cases and sometimes highly
valuable (the tiny promotion inputs). Repacking corrects substantial packing
conflicts: +1964 immediate points on kittens, +1702 on the asymmetric input,
and +8075 on the selected challenge after ordinary LS. Indexed exchanges often
add little immediately, but in kittens +3 was followed by +270 from final LS.
Evaluate repair chains, not just the immediate exchange score delta.

The main benchmark spent 24.167 s constructing, 87.718 s in ordinary LS,
31.567 s repacking and 21.170 s in exchanges (sum over inputs). Repacking was
skipped on nine inputs by the frozen operation estimate. Large capacity makes
pseudopolynomial DP expensive even with modest dimensions. Dense overlap makes
benefit recomputation, transfer indexing and full-objective checks expensive.
These are observed scale mechanisms, not measured causal feature predictors.

Future candidates: capacity-aware DP work estimation, cheaper exact benefit
refreshes, reusable state across consecutive repair stages and sparse transfer
indexes. Profile first and preserve deterministic score/tie controls. Do not
remove correctness checks simply to claim algorithm speed.

## An interpretable adaptive policy to evaluate later

1. Analyze dimensions, connected demand, size dispersion, cache tightness,
   degree and estimated DP/transfer work.
2. Construct the small fixed portfolio. Deduplicate identical placements.
3. Give each distinct start a small, equal repair allowance.
4. Allocate subsequent bounded repair batches to starts showing useful exact
   objective progress, while reserving occasional budget for alternatives.
5. Save the best validated placement after every batch. Never regress it.

Keep regret weight, LS passes/moves/order, repacking candidate limit/pass cap,
exchange pair/video/round caps and total budget configurable. Freeze adaptive
rules and feature thresholds before final testing. Use exact raw gains, not only
rounded score changes, to estimate progress. Capacity/overlap estimates can
inform work budgets, but currently there is insufficient evidence to infer which
constructor will win from those features alone.

## Honest evaluation design

`instance-features.csv` preserves raw structural features plus construction and
stage gains. Separate pilot/familiar official rows from novel evaluation rows.
Split by generator family and author when possible, not random near-duplicate
seeds alone. Reserve new unseen student instances and independently generated
families as a final test. Equalize total compute, use repeated timings, retain
negative outcomes, and compare against the strongest fixed configurations and
multistart controls. Actual external implementations would be more informative
than more variants of our own repair engine.

Evidence still missing: broad unseen-family performance, external competitors,
fairly tuned time-budget policies, diverse perturbation-based basin escape, and
reliable out-of-sample feature-to-strategy prediction. All are future research,
not features or superiority claims delivered by this mission.
