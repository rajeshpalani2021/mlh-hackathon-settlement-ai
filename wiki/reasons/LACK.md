---
id: reasons/LACK
type: reason
keys: [LACK, PEND, MT548, 24B]
title: LACK - lack of securities
source: General public ISO 15022 status reason knowledge
created: 2026-10-11
---
# PEND / LACK - insufficient securities

MT548 `:25D::SETT//PEND` with `:24B::PEND//LACK`: the delivering party does not have enough
securities in the account at the depository to settle.

- Not a data error in our instruction, so **no field change** is usually needed.
- If we are the seller, check positions and pending receipts; if the counterparty is short,
  chase the counterparty.
- Use historical settle stats for the counterparty + LACK to estimate when it will settle.
