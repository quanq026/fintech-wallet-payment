package ra.ledgerservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ra.contracts.ledger.Account;
import ra.contracts.ledger.CreateAccountRequest;
import ra.contracts.ledger.LedgerEntry;
import ra.contracts.ledger.Operation;
import ra.contracts.ledger.PostTransferRequest;
import ra.contracts.ledger.Reconciliation;

import java.util.List;
import java.util.Optional;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createAccount(CreateAccountRequest request) {
        jdbc.update("call create_ledger_account(?,?,?)",
                request.getAccountId(), request.getOwnerId(), request.getKind());
    }

    public Optional<Account> findAccount(String accountId) {
        List<Account> accounts = jdbc.query("""
                select a.account_id,a.owner_id,a.kind,a.currency,
                  coalesce((select sum(amount) from ledger_entries where account_id=a.account_id),0) balance
                from ledger_accounts a where account_id=?
                """, (result, row) -> Account.newBuilder()
                .setAccountId(result.getString("account_id"))
                .setOwnerId(result.getString("owner_id"))
                .setKind(result.getString("kind"))
                .setCurrency(result.getString("currency"))
                .setBalance(result.getBigDecimal("balance").toBigIntegerExact().longValueExact())
                .build(), accountId);
        return accounts.stream().findFirst();
    }

    public Optional<Operation> findOperation(String operationId) {
        List<Operation> operations = jdbc.query("select * from ledger_journals where operation_id=?",
                (result, row) -> Operation.newBuilder()
                        .setOperationId(result.getString("operation_id"))
                        .setJournalId(result.getString("journal_id"))
                        .setSourceAccountId(result.getString("source_account_id"))
                        .setDestinationAccountId(result.getString("destination_account_id"))
                        .setAmount(result.getLong("amount"))
                        .setCurrency(result.getString("currency"))
                        .build(), operationId);
        return operations.stream().findFirst();
    }

    public List<LedgerEntry> findEntries(String accountId) {
        return jdbc.query("select * from ledger_entries where account_id=? order by entry_id",
                (result, row) -> LedgerEntry.newBuilder()
                        .setEntryId(result.getLong("entry_id"))
                        .setJournalId(result.getString("journal_id"))
                        .setAccountId(accountId)
                        .setAmount(result.getLong("amount"))
                        .setCurrency(result.getString("currency"))
                        .setCreatedAt(result.getTimestamp("created_at").toInstant().toString())
                        .build(), accountId);
    }

    public void postTransfer(PostTransferRequest request, String fingerprint) {
        jdbc.update("call post_ledger_transfer(?,?,?,?,?,?)", request.getOperationId(), fingerprint,
                request.getSourceAccountId(), request.getDestinationAccountId(),
                request.getAmount(), request.getCurrency());
    }

    public Reconciliation reconcile() {
        return jdbc.queryForObject("""
                select
                  (select count(*) from ledger_journals) journal_count,
                  (select count(*) from ledger_entries) entry_count,
                  (select coalesce(sum(amount),0) from ledger_entries) total_amount,
                  (select count(*) from (
                    select j.journal_id from ledger_journals j left join ledger_entries e on e.journal_id=j.journal_id
                    group by j.journal_id,j.source_account_id,j.destination_account_id,j.amount,j.currency
                    having count(e.entry_id)<>2 or coalesce(sum(e.amount),0)<>0
                      or sum(case when e.account_id=j.source_account_id and e.amount=-j.amount and e.currency=j.currency then 1 else 0 end)<>1
                      or sum(case when e.account_id=j.destination_account_id and e.amount=j.amount and e.currency=j.currency then 1 else 0 end)<>1
                  ) bad) unbalanced_journals,
                  (select count(*) from (
                    select a.account_id from ledger_accounts a join ledger_entries e on e.account_id=a.account_id
                    where a.kind<>'CLEARING' group by a.account_id having sum(e.amount)<0
                  ) negative_accounts) negative_user_accounts
                """, (result, row) -> Reconciliation.newBuilder()
                .setJournalCount(result.getLong("journal_count"))
                .setEntryCount(result.getLong("entry_count"))
                .setTotalAmount(result.getBigDecimal("total_amount").toBigIntegerExact().longValueExact())
                .setUnbalancedJournals(result.getLong("unbalanced_journals"))
                .setNegativeUserAccounts(result.getLong("negative_user_accounts"))
                .build());
    }
}
