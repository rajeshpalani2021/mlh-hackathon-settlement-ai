---
id: fields/95R-REAG-DEAG
type: field
keys: [95R, REAG, DEAG, DTCYID, AGENT]
title: 95R REAG / DEAG - receiving / delivering agent
source: General public MT541/MT543 field knowledge
created: 2026-10-11
---
# :95R::REAG/DTCYID/ and :95R::DEAG/DTCYID/

Sequence E1 (SETPRTY). The counterparty's agent identified by a proprietary code: for DTC,
the participant number with data source scheme `DTCYID`.

- MT541 (receive against payment, our BUY): the counterparty side is the **delivering** agent, `DEAG`.
- MT543 (deliver against payment, our SELL): the counterparty side is the **receiving** agent, `REAG`.

A wrong participant number leads to an unmatched instruction (`NMAT`, reason `DEPT` or `ICAG`).
