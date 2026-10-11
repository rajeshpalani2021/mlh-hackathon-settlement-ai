---
id: fields/36B-SETT
type: field
keys: [36B, SETT, QUANTITY, UNIT, DQUA]
title: 36B SETT - quantity to be settled
source: General public MT541/MT543 field knowledge
created: 2026-10-11
---
# :36B::SETT//UNIT/

Sequence C (FIAC). Number of units to settle, SWIFT decimal format (comma separator,
trailing comma for whole numbers: `1000,`). Must equal the counterparty's quantity or the
instruction stays unmatched with `DQUA`.
