package com.edumy.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import com.edumy.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Proof of {@link SqlStatementCounter}: JPA and JdbcClient statements are counted, per thread, from {@code reset()}. */
@MySqlSpringBootTest
class SqlStatementCounterIT {

    @Autowired
    SqlStatementCounter sql;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcClient jdbc;

    @Test
    void count_jpaAndJdbcClientQueries_eachCountedOnce() {
        sql.reset();
        assertThat(sql.count()).isZero();

        users.findStatusById(-1L); // JPA (Hibernate) query
        assertThat(sql.count()).isEqualTo(1);
        assertThat(sql.statements().getFirst()).containsIgnoringCase("from users");

        jdbc.sql("SELECT COUNT(*) FROM roles").query(Long.class).single(); // JdbcClient
        users.count();
        assertThat(sql.count()).isEqualTo(3);
        assertThat(sql.statements().get(1)).isEqualTo("SELECT COUNT(*) FROM roles");

        sql.reset();
        assertThat(sql.count()).isZero();
    }

    @Test
    void count_statementsOfOtherThreads_notCounted() {
        sql.reset();
        CompletableFuture.runAsync(() -> jdbc.sql("SELECT 1").query(Integer.class).single()).join();
        assertThat(sql.count()).isZero();
    }
}
