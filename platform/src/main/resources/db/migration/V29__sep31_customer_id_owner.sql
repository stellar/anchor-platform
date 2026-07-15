CREATE TABLE sep31_customer_id_owner (
    customer_id     VARCHAR(255) NOT NULL PRIMARY KEY,
    creator_account VARCHAR(255) NOT NULL,
    creator_memo    VARCHAR(255),
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO sep31_customer_id_owner (customer_id, creator_account, creator_memo)
SELECT DISTINCT ON (customer_id)
       customer_id,
       COALESCE(client_name, creator::jsonb ->> 'account') AS creator_account,
       creator::jsonb ->> 'memo' AS creator_memo
  FROM (
    SELECT receiver_id AS customer_id, creator, client_name, started_at, id
      FROM sep31_transaction
     WHERE receiver_id IS NOT NULL
    UNION ALL
    SELECT sender_id AS customer_id, creator, client_name, started_at, id
      FROM sep31_transaction
     WHERE sender_id IS NOT NULL
  ) refs
 WHERE COALESCE(client_name, creator::jsonb ->> 'account') IS NOT NULL
 ORDER BY customer_id, started_at ASC NULLS LAST, id ASC
ON CONFLICT (customer_id) DO NOTHING;
