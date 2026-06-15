WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO ON

ALTER SESSION SET CONTAINER = FREEPDB1;

DECLARE
  v_tablespace_count PLS_INTEGER;
  v_user_count PLS_INTEGER;
BEGIN
  SELECT COUNT(*)
  INTO   v_tablespace_count
  FROM   dba_tablespaces
  WHERE  tablespace_name = 'SEMCACHE_DATA';

  IF v_tablespace_count = 0 THEN
    EXECUTE IMMEDIATE q'[
      CREATE TABLESPACE semcache_data
      DATAFILE '/opt/oracle/oradata/FREE/FREEPDB1/semcache_data01.dbf'
      SIZE 100M
      AUTOEXTEND ON NEXT 50M MAXSIZE 1G
      SEGMENT SPACE MANAGEMENT AUTO
    ]';
  END IF;

  SELECT COUNT(*)
  INTO   v_user_count
  FROM   dba_users
  WHERE  username = 'SEMCACHE_APP';

  IF v_user_count = 0 THEN
    EXECUTE IMMEDIATE '
      CREATE USER semcache_app IDENTIFIED BY "SemCache_26ai_Demo"
        DEFAULT TABLESPACE semcache_data
        QUOTA UNLIMITED ON semcache_data';
  ELSE
    EXECUTE IMMEDIATE 'ALTER USER semcache_app IDENTIFIED BY "SemCache_26ai_Demo" ACCOUNT UNLOCK';
    EXECUTE IMMEDIATE 'ALTER USER semcache_app DEFAULT TABLESPACE semcache_data QUOTA UNLIMITED ON semcache_data';
  END IF;
END;
/

GRANT CREATE SESSION TO semcache_app;
GRANT CREATE TABLE TO semcache_app;
GRANT CREATE SEQUENCE TO semcache_app;

EXIT
