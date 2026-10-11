---
id: concepts/mt548-status-flow
type: concept
keys: [MT548, 25D, 24B, MTCH, MACH, NMAT, PEND, STATUS, ACK, NAK]
title: Instruction lifecycle - ACK/NAK and MT548 statuses
source: General public SWIFT / ISO 15022 knowledge
created: 2026-10-11
---
# Instruction lifecycle

1. We send MT541 (buy) or MT543 (sell).
2. Network acknowledgement: `{451:0}` = ACK, `{451:1}{405:<code>}` = NAK. A NAKed message
   never reached the custodian and must be corrected and resent.
3. MT548 status advices are linked by `:20C::RELA//<our SEME>`:
   - `:25D::MTCH//MACH` matched; `:25D::MTCH//NMAT` unmatched + `:24B::NMAT//<reason>`
   - `:25D::SETT//PEND` pending + `:24B::PEND//<reason>` (LACK, CLAC, MONY)
4. Settled (`:25D::SETT//STLD` in this demo).
