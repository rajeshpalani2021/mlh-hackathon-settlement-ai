---
id: reasons/NMAT-DQUA
type: reason
keys: [DQUA, NMAT, MTCH, MT548, 24B, 36B, QUANTITY]
title: NMAT / DQUA - quantity mismatch
source: General public ISO 15022 status reason knowledge
created: 2026-10-11
---
# Unmatched - DQUA (quantity disagreement)

MT548 `:25D::MTCH//NMAT` with `:24B::NMAT//DQUA`: our instruction did not match the
counterparty's because the **settlement quantity** (`:36B::SETT//UNIT/`) differs.

- Compare our `:36B::SETT` with the trade confirmation or the counterparty's instruction.
- If the counterparty's quantity is not in our data, do **not** guess a new value:
  escalate to the front office / counterparty and keep confidence low.
