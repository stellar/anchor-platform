ALTER TABLE sep31_transaction ALTER COLUMN fields TYPE JSON USING fields::json;
ALTER TABLE sep31_transaction ALTER COLUMN required_info_updates TYPE JSON USING required_info_updates::json;
