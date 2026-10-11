---
id: fields/98A-SETT
type: field
keys: [98A, SETT, TRAD, DATE]
title: 98A SETT / TRAD - settlement and trade date
source: General public MT541/MT543 field knowledge
created: 2026-10-11
---
# :98A::SETT// and :98A::TRAD//

Sequence B (TRADDET), format `YYYYMMDD`.

- `TRAD` - trade date. Overnight executions at or after 8:00 PM ET belong to the **next** trade date.
- `SETT` - contractual settlement date. US equities settle **T+1** business day.

A wrong date usually shows up as an unmatched instruction (`NMAT` with `DDAT` or `DTRD`).
See concepts/t1-cutoffs.
