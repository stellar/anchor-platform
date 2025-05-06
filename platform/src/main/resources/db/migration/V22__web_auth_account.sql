ALTER TABLE sep6_transaction RENAME sep10_account TO web_auth_account;
ALTER TABLE sep6_transaction RENAME sep10_account_memo TO web_auth_account_memo;
ALTER TABLE sep24_transaction RENAME sep10account TO web_auth_account;
ALTER TABLE sep24_transaction RENAME sep10account_memo TO web_auth_account_memo;
CREATE TABLE nonce(
                      id VARCHAR(255) NOT NULL,
                      used BOOLEAN NOT NULL,
                      expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
                      CONSTRAINT pk_nonce PRIMARY KEY (id)
);

CREATE INDEX idx_nonce_expires_at ON nonce (expires_at);