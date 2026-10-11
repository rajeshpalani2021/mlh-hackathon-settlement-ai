---
id: codes/nak-T27
type: code
keys: [T27, NAK, BIC, 95P, PSET, BUYR, SELL]
title: NAK T27 - invalid or incorrectly formatted BIC
source: General public SWIFT FIN error code knowledge
created: 2026-10-11
---
# NAK 405 T27 - BIC incorrect

The FIN network rejected the message (`{451:1}{405:T27}`) because a BIC in the text
block is not a valid BIC format. The message never reached the custodian.

**BIC format:** 4 letters institution + 2 letters country + 2 alphanumeric location
+ optional 3 alphanumeric branch, i.e. 8 or 11 characters
(`^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$`).

**Where to look:** every `:95P:` party field (`PSET`, `BUYR`, `SELL`).
A 7- or 10-character value is the classic cause (a dropped character).

**Typical fix:** correct the single BIC field, keep everything else unchanged, resend as a new
message (new SEME reference). Common example: place of settlement for DTC is `DTCYUS33`.
