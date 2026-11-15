package com.edumy.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.edumy.support.MySqlSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-010 (P10 DT-B1): every application transaction runs READ COMMITTED (Hikari {@code transaction-isolation}), so a
 * read after a row lock sees what was committed while waiting. The lost-update scenarios themselves are proved by
 * {@code CoInstructorWriteIT#accept_racingARemoveThatHoldsTheCourseLock_409NotPending_staysRemoved_noLostUpdate} and
 * {@code LessonWriteIT#patch_racingAnotherEditThatHoldsTheCourseLock_keepsTheOtherEdit_noLostUpdate}.
 */
@MySqlSpringBootTest
class TransactionIsolationIT {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void defaultIsolation_isReadCommitted_insideAndOutsideTransactions() {
        assertThat(isolation()).isEqualTo("READ-COMMITTED");
        String inside = new TransactionTemplate(txManager).execute(status -> isolation());
        assertThat(inside).isEqualTo("READ-COMMITTED");
    }

    private String isolation() {
        return jdbc.sql("SELECT @@transaction_isolation").query(String.class).single();
    }
}
