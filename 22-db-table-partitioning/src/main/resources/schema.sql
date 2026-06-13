-- Just ensure the schema exists. The interesting DDL — the partitioned
-- tables themselves — is created per-demo from PartitionService.seedXxx()
-- so each demo is self-contained and the partitioning DDL is visible
-- in code.
create schema if not exists m22_partition;
