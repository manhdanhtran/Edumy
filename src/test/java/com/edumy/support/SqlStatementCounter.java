package com.edumy.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Test-only counter of the JDBC statements the application executes (docs/ai/07 "SqlStatementCounter"): a
 * {@link BeanPostProcessor} that wraps the application {@link DataSource} so that every {@code execute*} call on a
 * {@link Statement} / {@link PreparedStatement} / {@link CallableStatement} is counted. JPA (Hibernate) and
 * {@code JdbcClient} both go through it. No dependency needed (JDK proxies); {@code executeBatch} counts as one.
 * Connection-level calls ({@code setReadOnly}, {@code commit}, driver-internal queries) are not statements and are not
 * counted.
 * <p><b>Per thread:</b> {@link #reset()} starts a measurement for the calling thread and {@link #count()} returns the
 * statements executed <b>on that thread</b> since then. MockMvc runs the request on the test thread, so background
 * work (async mail listeners of other tests) never disturbs a measurement.
 * <p><b>Caches:</b> the security filter reads {@code identity.user-status} / {@code identity.session-status} (30 s)
 * and access beans read {@code identity.active-roles} (60 s) through Caffeine; a cold cache costs a statement. Make
 * the measurement deterministic by sending the same request once to warm up, then {@code reset()}, then the measured
 * request:
 * <pre>{@code
 * mvc.perform(get(URL).header(AUTHORIZATION, bearer)).andExpect(status().isOk()); // warm-up
 * sql.reset();
 * mvc.perform(get(URL + "?size=5").header(AUTHORIZATION, bearer)).andExpect(status().isOk());
 * assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3);
 * }</pre>
 * Registered by {@link MySqlSpringBootTest} and {@link MySqlSeedSpringBootTest}; inject it with {@code @Autowired}.
 */
public class SqlStatementCounter implements BeanPostProcessor {

    private static final ThreadLocal<List<String>> EXECUTED = ThreadLocal.withInitial(ArrayList::new);

    /** Starts a new measurement on the calling thread. */
    public void reset() {
        EXECUTED.get().clear();
    }

    /** Statements executed on the calling thread since the last {@link #reset()}. */
    public int count() {
        return EXECUTED.get().size();
    }

    /** SQL text of those statements, in order (for assertion messages). */
    public List<String> statements() {
        return List.copyOf(EXECUTED.get());
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)) {
            return new CountingDataSource(dataSource);
        }
        return bean;
    }

    private static void record(String sql) {
        EXECUTED.get().add(sql == null ? "?" : sql);
    }

    /** {@link DelegatingDataSource} keeps {@code unwrap}/{@code isWrapperFor} working (Hikari metrics, health). */
    static final class CountingDataSource extends DelegatingDataSource {

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return countingConnection(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return countingConnection(super.getConnection(username, password));
        }
    }

    private static Connection countingConnection(Connection target) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object result = invoke(target, method, args);
            String name = method.getName();
            if (result instanceof Statement statement
                    && (name.equals("createStatement") || name.equals("prepareStatement")
                    || name.equals("prepareCall"))) {
                String sql = name.equals("createStatement") ? null : (String) args[0];
                return countingStatement(statement, sql, method.getReturnType());
            }
            return result;
        };
        return (Connection) Proxy.newProxyInstance(SqlStatementCounter.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    private static Object countingStatement(Statement target, String preparedSql, Class<?> type) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().startsWith("execute")) {
                boolean hasSqlArg = args != null && args.length > 0 && args[0] instanceof String;
                record(hasSqlArg ? (String) args[0] : preparedSql);
            }
            return invoke(target, method, args);
        };
        return Proxy.newProxyInstance(SqlStatementCounter.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
