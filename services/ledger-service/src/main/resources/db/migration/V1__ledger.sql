CREATE TABLE ledger_accounts (
 account_id varchar(100) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_id varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind varchar(10) NOT NULL CHECK(kind IN ('USER','MERCHANT','CLEARING')),
 currency varchar(3) NOT NULL DEFAULT 'VND' CHECK(currency='VND')
) ENGINE=InnoDB;
CREATE TABLE ledger_journals (
 journal_id varchar(36) PRIMARY KEY,
 operation_id varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 fingerprint varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_account_id varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 destination_account_id varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount bigint NOT NULL CHECK(amount>0),
 currency varchar(3) NOT NULL CHECK(currency='VND'),
 created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(source_account_id) REFERENCES ledger_accounts(account_id),
 FOREIGN KEY(destination_account_id) REFERENCES ledger_accounts(account_id),
 CHECK(source_account_id<>destination_account_id)
) ENGINE=InnoDB;
CREATE TABLE ledger_entries (
 entry_id bigint AUTO_INCREMENT PRIMARY KEY,
 journal_id varchar(36) NOT NULL,
 account_id varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount bigint NOT NULL CHECK(amount<>0),
 currency varchar(3) NOT NULL CHECK(currency='VND'),
 created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(journal_id) REFERENCES ledger_journals(journal_id),
 FOREIGN KEY(account_id) REFERENCES ledger_accounts(account_id),
 UNIQUE(journal_id,account_id),
 INDEX ledger_entries_account_idx(account_id,entry_id)
) ENGINE=InnoDB;
DELIMITER $$
CREATE TRIGGER immutable_entries_update BEFORE UPDATE ON ledger_entries FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ledger entries are immutable'; END$$
CREATE TRIGGER immutable_entries_delete BEFORE DELETE ON ledger_entries FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ledger entries are immutable'; END$$
CREATE TRIGGER immutable_journals_update BEFORE UPDATE ON ledger_journals FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ledger journals are immutable'; END$$
CREATE TRIGGER immutable_journals_delete BEFORE DELETE ON ledger_journals FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ledger journals are immutable'; END$$
CREATE TRIGGER immutable_accounts_update BEFORE UPDATE ON ledger_accounts FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Account metadata is immutable'; END$$
CREATE TRIGGER immutable_accounts_delete BEFORE DELETE ON ledger_accounts FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Account metadata is immutable'; END$$
CREATE PROCEDURE create_ledger_account(IN p_id varchar(100), IN p_owner varchar(100), IN p_kind varchar(10))
SQL SECURITY DEFINER
BEGIN
 IF p_id NOT REGEXP '^[A-Za-z0-9_-]{1,100}$' OR p_owner NOT REGEXP '^[A-Za-z0-9_-]{1,100}$' OR p_kind NOT IN ('USER','MERCHANT','CLEARING') THEN
  SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT='Invalid account';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM ledger_accounts WHERE account_id=p_id) THEN
  BEGIN
   DECLARE CONTINUE HANDLER FOR 1062 BEGIN END;
   INSERT INTO ledger_accounts(account_id,owner_id,kind) VALUES(p_id,p_owner,p_kind);
  END;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM ledger_accounts WHERE account_id=p_id AND BINARY owner_id=BINARY p_owner AND kind=p_kind) THEN
  SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT='Account ID already has different metadata';
 END IF;
END$$
CREATE PROCEDURE post_ledger_transfer(IN p_operation varchar(100), IN p_fingerprint varchar(64), IN p_source varchar(100), IN p_destination varchar(100), IN p_amount bigint, IN p_currency varchar(3))
SQL SECURITY DEFINER
posting: BEGIN
 DECLARE v_existing varchar(64);
 DECLARE v_first varchar(100);
 DECLARE v_second varchar(100);
 DECLARE v_locked varchar(100);
 DECLARE v_kind varchar(10);
 DECLARE v_source_balance decimal(65,0);
 DECLARE v_destination_balance decimal(65,0);
 DECLARE v_journal varchar(36);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF p_amount<=0 OR BINARY p_currency<>BINARY 'VND' OR BINARY p_source=BINARY p_destination THEN SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT='Invalid transfer'; END IF;
 SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
 START TRANSACTION;
 SELECT MAX(fingerprint) INTO v_existing FROM ledger_journals WHERE operation_id=p_operation;
 IF v_existing IS NOT NULL THEN
  IF BINARY v_existing<>BINARY p_fingerprint THEN SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT='Operation ID reused with different payload'; END IF;
  COMMIT;
  LEAVE posting;
 END IF;
 SET v_first=IF(BINARY p_source<BINARY p_destination,p_source,p_destination), v_second=IF(BINARY p_source<BINARY p_destination,p_destination,p_source);
 SET v_locked=NULL;
 SELECT account_id INTO v_locked FROM ledger_accounts WHERE account_id=v_first FOR UPDATE;
 IF v_locked IS NULL THEN SIGNAL SQLSTATE '45004' SET MESSAGE_TEXT='Account not found'; END IF;
 SET v_locked=NULL;
 SELECT account_id INTO v_locked FROM ledger_accounts WHERE account_id=v_second FOR UPDATE;
 IF v_locked IS NULL THEN SIGNAL SQLSTATE '45004' SET MESSAGE_TEXT='Account not found'; END IF;
 SELECT kind INTO v_kind FROM ledger_accounts WHERE account_id=p_source;
 SELECT COALESCE(SUM(amount),0) INTO v_source_balance FROM ledger_entries WHERE account_id=p_source;
 SELECT COALESCE(SUM(amount),0) INTO v_destination_balance FROM ledger_entries WHERE account_id=p_destination;
 IF v_kind<>'CLEARING' AND v_source_balance-p_amount<0 THEN SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='Insufficient funds'; END IF;
 IF v_source_balance-p_amount < -9223372036854775808 OR v_destination_balance+p_amount > 9223372036854775807 THEN SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT='Balance overflow'; END IF;
 SET v_journal=UUID();
 INSERT INTO ledger_journals(journal_id,operation_id,fingerprint,source_account_id,destination_account_id,amount,currency)
  VALUES(v_journal,p_operation,p_fingerprint,p_source,p_destination,p_amount,p_currency);
 INSERT INTO ledger_entries(journal_id,account_id,amount,currency) VALUES(v_journal,p_source,-p_amount,p_currency),(v_journal,p_destination,p_amount,p_currency);
 COMMIT;
END$$
DELIMITER ;
