CREATE UNIQUE INDEX uq_sep24_pending_memo
  ON sep24_transaction (withdraw_anchor_account, memo_type, memo)
  WHERE status = 'pending_user_transfer_start';

CREATE UNIQUE INDEX uq_sep6_pending_memo
  ON sep6_transaction (withdraw_anchor_account, memo_type, memo)
  WHERE status = 'pending_user_transfer_start';

CREATE UNIQUE INDEX uq_sep31_pending_memo
  ON sep31_transaction (to_account, stellar_memo_type, stellar_memo)
  WHERE status = 'pending_sender';
