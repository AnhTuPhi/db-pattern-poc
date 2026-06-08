-- Loaded on first Oracle start. Creates the app user inside FREEPDB1.
-- Oracle puts you in CDB$ROOT by default; we want PDB.

alter session set container = FREEPDB1;

create user appuser identified by "AppUser123"
  default tablespace users
  temporary tablespace temp
  quota unlimited on users;

grant connect, resource, create session, create table, create sequence,
      create procedure, create view, create trigger, create type, create synonym
  to appuser;

-- Diagnostic privileges needed by the slow-query-hunting + query-plan POCs.
grant select on v_$session to appuser;
grant select on v_$sql to appuser;
grant select on v_$sql_plan to appuser;
grant select on v_$sql_plan_statistics_all to appuser;
grant select on v_$sqlarea to appuser;
grant select on dba_hist_sqlstat to appuser;
grant select_catalog_role to appuser;
grant advisor to appuser;
grant administer sql tuning set to appuser;
