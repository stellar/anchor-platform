ALTER TABLE exchange_quote
    ADD CONSTRAINT uq_exchange_quote_transaction_id UNIQUE (transaction_id);
