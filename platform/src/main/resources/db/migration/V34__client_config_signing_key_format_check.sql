ALTER TABLE client_signing_key
    ADD CONSTRAINT chk_client_signing_key_format CHECK (signing_key ~ '^G[A-Z2-7]{55}$') NOT VALID;

ALTER TABLE client_destination_account
    ADD CONSTRAINT chk_client_destination_account_format CHECK (destination_account ~ '^G[A-Z2-7]{55}$') NOT VALID;
