package com.edumy.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.Set;

import com.edumy.shared.jpa.CodeEnum;
import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proof of {@link MySqlSeedSpringBootTest}: the whole app runs on {@code edumy_seed} (seed rows visible), the role map of
 * {@link SeedUserTokens} matches the seed, and an authenticated request writes nothing.
 */
@MySqlSeedSpringBootTest
class MySqlSeedSpringBootTestIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    @Test
    void context_readsSeedDatabase() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo(SharedMySql.SEED_DATABASE);
        assertThat(jdbc.sql("SELECT email FROM users WHERE id = 1").query(String.class).single())
                .isEqualTo("admin@edumy.vn");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single())
                .isEqualTo(SeedUserTokens.LAST_USER_ID);
    }

    @Test
    void seedRoles_matchActiveRolesInDatabase() {
        for (long id = 1; id <= SeedUserTokens.LAST_USER_ID; id++) {
            Set<RoleCode> inDb = EnumSet.noneOf(RoleCode.class);
            jdbc.sql("""
                            SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                            WHERE ur.user_id = ? AND ur.is_active
                            """)
                    .param(id)
                    .query(String.class)
                    .list()
                    .forEach(code -> inDb.add(CodeEnum.fromCode(RoleCode.class, code)));
            assertThat(SeedUserTokens.seedRoles(id)).as("user %d", id).isEqualTo(inDb);
        }
    }

    @Test
    void bearer_authenticatedRequest_writesNothing() throws Exception {
        long sessionsBefore = jdbc.sql("SELECT COUNT(*) FROM refresh_tokens").query(Long.class).single();

        sql.reset();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.STUDENT)))
                .andExpect(status().isOk());

        assertThat(sql.statements())
                .allSatisfy(statement -> assertThat(statement.strip()).startsWithIgnoringCase("select"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM refresh_tokens").query(Long.class).single()).isEqualTo(sessionsBefore);
    }
}
