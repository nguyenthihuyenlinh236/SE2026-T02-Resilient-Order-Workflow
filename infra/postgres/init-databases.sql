-- PostgreSQL's entrypoint executes this only on a fresh data volume.
-- Databases are owned by POSTGRES_USER, the local development role.
CREATE DATABASE order_db;
CREATE DATABASE inventory_db;
CREATE DATABASE payment_db;
