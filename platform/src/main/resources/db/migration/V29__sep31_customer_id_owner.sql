CREATE TABLE sep31_customer_id_owner (
    customer_id     VARCHAR(255) NOT NULL PRIMARY KEY,
    creator_account VARCHAR(255) NOT NULL,
    creator_memo    VARCHAR(255),
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
);
