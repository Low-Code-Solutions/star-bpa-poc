-- Runs once, on first start of an empty data volume.
-- One PostgreSQL server, two databases: neither app reads the other's tables.
-- Credentials here must match .env.example (the init dir cannot read .env).

CREATE ROLE camunda LOGIN PASSWORD 'camunda';
CREATE DATABASE camunda OWNER camunda;

CREATE ROLE bpa LOGIN PASSWORD 'bpa';
CREATE DATABASE bpa OWNER bpa;

\connect bpa
CREATE SCHEMA IF NOT EXISTS bpa AUTHORIZATION bpa;
