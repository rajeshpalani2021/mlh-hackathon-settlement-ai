---
id: fields/95P-PSET
type: field
keys: [95P, PSET, BIC, T27, SETPRTY]
title: 95P PSET - place of settlement
source: General public MT541/MT543 field knowledge
created: 2026-10-11
---
# :95P::PSET// - place of settlement

Sequence E1 (SETPRTY). The BIC of the central securities depository where settlement
takes place. For US equities this is DTC: **`DTCYUS33`** (8 characters).

A wrong length (e.g. `DTCYUS3`) fails BIC validation and the message is NAKed with `T27`.
