CREATE TABLE client_config (
    name                  VARCHAR(255) NOT NULL PRIMARY KEY,
    type                  VARCHAR(32)  NOT NULL,
    allow_any_destination BOOLEAN      NOT NULL DEFAULT FALSE,
    callback_url_sep6     VARCHAR(255),
    callback_url_sep24    VARCHAR(255),
    callback_url_sep31    VARCHAR(255),
    callback_url_sep12    VARCHAR(255)
);

CREATE TABLE client_domain (
    client_name VARCHAR(255) NOT NULL REFERENCES client_config (name) ON DELETE CASCADE,
    domain      VARCHAR(255) NOT NULL,
    PRIMARY KEY (client_name, domain)
);

CREATE UNIQUE INDEX idx_client_domain_domain ON client_domain (domain);

CREATE TABLE client_signing_key (
    client_name VARCHAR(255) NOT NULL REFERENCES client_config (name) ON DELETE CASCADE,
    signing_key VARCHAR(56)  NOT NULL,
    PRIMARY KEY (client_name, signing_key)
);

CREATE UNIQUE INDEX idx_client_signing_key_key ON client_signing_key (signing_key);

CREATE TABLE client_destination_account (
    client_name         VARCHAR(255) NOT NULL REFERENCES client_config (name) ON DELETE CASCADE,
    destination_account VARCHAR(56)  NOT NULL,
    PRIMARY KEY (client_name, destination_account)
);
