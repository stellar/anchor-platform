CREATE TABLE nonce(
    id VARCHAR(255) NOT NULL,
    used BOOLEAN NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_nonce PRIMARY KEY (id)
);

CREATE INDEX idx_nonce_expires_at ON nonce (expires_at);