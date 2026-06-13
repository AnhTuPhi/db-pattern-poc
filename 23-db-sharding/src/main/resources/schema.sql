-- Create the four shard schemas. The orders table itself is created
-- per-shard by ShardingService.seed() so the demo is self-contained
-- and the per-shard DDL is visible in code.
create schema if not exists m23_s0;
create schema if not exists m23_s1;
create schema if not exists m23_s2;
create schema if not exists m23_s3;
