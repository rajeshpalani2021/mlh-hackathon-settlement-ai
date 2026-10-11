---
id: index
type: concept
keys: [INDEX, WIKI]
title: Settlement operations knowledge wiki
source: Settle hackathon team (synthetic demo content)
created: 2026-10-11
---
# Settlement operations wiki

Short pages the investigator agent searches before proposing a fix.

- **codes/** - SWIFT FIN network acknowledgement errors (NAK `405` codes), e.g. [nak-T27](codes/nak-T27.md)
- **reasons/** - MT548 status reason codes (`:24B:`): [LACK](reasons/LACK.md), [CLAC](reasons/CLAC.md), [MONY](reasons/MONY.md), [NMAT-DQUA](reasons/NMAT-DQUA.md)
- **fields/** - MT541/MT543 fields: [95P PSET](fields/95P-PSET.md), [98A SETT](fields/98A-SETT.md), [95R REAG/DEAG](fields/95R-REAG-DEAG.md), [36B SETT](fields/36B-SETT.md)
- **concepts/** - [US T+1 cutoffs](concepts/t1-cutoffs.md), [Instruction lifecycle](concepts/mt548-status-flow.md)
- **recipes/** - verified fixes, written automatically by code after a corrected message was ACKed. Recipes are checked first.
