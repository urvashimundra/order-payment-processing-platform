-- Local-only users and databases. Passwords match local-config/*.yaml
CREATE USER merchant_user   WITH PASSWORD 'merchant_pass';
CREATE USER payment_user    WITH PASSWORD 'payment_pass';
CREATE USER operations_user WITH PASSWORD 'operations_pass';
CREATE USER vault_user      WITH PASSWORD 'vault_pass';

CREATE DATABASE "pay-merchant-db"   OWNER merchant_user;
CREATE DATABASE "pay-payment-db"    OWNER payment_user;
CREATE DATABASE "pay-operations-db" OWNER operations_user;
CREATE DATABASE "pay-vault-db"      OWNER vault_user;
