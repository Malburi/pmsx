package kr.pmsx.common.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * SQL 을 실행 직전에 {@link SqlTranslator} 로 번역하는 DataSource 래퍼 (Oracle·Tibero·SQL Server 전용).
 *
 * <ul>
 *   <li>Connection.prepareStatement/prepareCall, Statement.execute* 의 SQL 을 번역</li>
 *   <li>번역으로 '?' 순서가 바뀌면 setXxx(index, …) 를 새 위치로 재배치(같은 값을 여러 위치에 넣는 경우 포함)</li>
 *   <li>불리언 파라미터는 1/0 으로 바인딩 (컬럼이 NUMBER(1)/BIT)</li>
 * </ul>
 * 대상 DataSource 는 트랜잭션 연결을 돌려주는 TransactionAwareDataSourceProxy 여야 한다.
 */
public class DialectDataSource extends DelegatingDataSource {

    /** 실행되는 SQL 을 관찰하는 훅 (SQL Server 실DB 재생 검증용). 번역 전 원래 SQL 과 원래 순서의 바인딩 값. */
    public interface SqlListener {
        void executed(String originalSql, Object[] params);
    }

    private static volatile SqlListener listener;

    public static void setListener(SqlListener l) {
        listener = l;
    }

    private final SqlTranslator translator;
    private final Map<String, SqlTranslator.Translation> cache = new ConcurrentHashMap<>();

    public DialectDataSource(DataSource target, SqlTranslator translator) {
        super(target);
        this.translator = translator;
    }

    SqlTranslator.Translation translate(String sql) {
        if (cache.size() > 10_000) {
            cache.clear();
        }
        return cache.computeIfAbsent(sql, translator::translate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection target) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, new ConnectionHandler(target));
    }

    private static Object invoke(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String name = m.getName();
            if (name.equals("unwrap") && args != null && args[0] == Connection.class) {
                return target;
            }
            if ((name.equals("prepareStatement") || name.equals("prepareCall")) && args != null
                    && args[0] instanceof String sql) {
                SqlTranslator.Translation t = translate(sql);
                Object[] a = args.clone();
                a[0] = t.sql();
                PreparedStatement ps = (PreparedStatement) DialectDataSource.invoke(target, m, a);
                Class<?> iface = name.equals("prepareCall") ? CallableStatement.class : PreparedStatement.class;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {iface},
                        new PreparedHandler(ps, t, sql));
            }
            if (name.equals("createStatement")) {
                Statement st = (Statement) DialectDataSource.invoke(target, m, args);
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                        new StatementHandler(st));
            }
            return DialectDataSource.invoke(target, m, args);
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Statement target;

        StatementHandler(Statement target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String name = m.getName();
            if ((name.startsWith("execute") || name.equals("addBatch")) && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                Object[] a = args.clone();
                a[0] = translate(sql).sql();
                SqlListener l = listener;
                if (l != null) {
                    l.executed(sql, new Object[0]);
                }
                return DialectDataSource.invoke(target, m, a);
            }
            return DialectDataSource.invoke(target, m, args);
        }
    }

    private static final class PreparedHandler implements InvocationHandler {
        private final PreparedStatement target;
        private final SqlTranslator.Translation t;
        private final String originalSql;
        private final Object[] bound;           // 원래 순서의 바인딩 값 (재생 훅용)
        private final List<Object[]> batch = new ArrayList<>();

        PreparedHandler(PreparedStatement target, SqlTranslator.Translation t, String originalSql) {
            this.target = target;
            this.t = t;
            this.originalSql = originalSql;
            this.bound = new Object[t.originalParamCount()];
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String name = m.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer idx
                    && m.getParameterTypes()[0] == int.class) {
                return setParam(m, idx, args);
            }
            if (name.equals("getParameterMetaData")) {
                ParameterMetaData md = target.getParameterMetaData();
                if (!t.remapped()) {
                    return md;
                }
                return Proxy.newProxyInstance(ParameterMetaData.class.getClassLoader(),
                        new Class<?>[] {ParameterMetaData.class}, (p, mm, aa) -> {
                            if (aa != null && aa.length > 0 && aa[0] instanceof Integer i) {
                                Object[] a2 = aa.clone();
                                a2[0] = firstPosition(i);
                                return DialectDataSource.invoke(md, mm, a2);
                            }
                            return DialectDataSource.invoke(md, mm, aa);
                        });
            }
            if (name.equals("clearParameters")) {
                Arrays.fill(bound, null);
            }
            SqlListener l = listener;
            if (l != null) {
                if (name.equals("addBatch") && (args == null || args.length == 0)) {
                    batch.add(bound.clone());
                } else if (name.equals("executeBatch")) {
                    for (Object[] b : batch) {
                        l.executed(originalSql, b);
                    }
                    batch.clear();
                } else if (name.startsWith("execute") && (args == null || args.length == 0)) {
                    l.executed(originalSql, bound.clone());
                }
            }
            return DialectDataSource.invoke(target, m, args);
        }

        private int firstPosition(int original) {
            int[] map = t.paramMap();
            for (int j = 0; j < map.length; j++) {
                if (map[j] == original) {
                    return j + 1;
                }
            }
            return original;
        }

        private Object setParam(Method m, int original, Object[] args) throws Throwable {
            Method method = m;
            Object[] a = args;
            // 불리언 → 1/0 (NUMBER(1)/BIT)
            if (m.getName().equals("setBoolean")) {
                method = PreparedStatement.class.getMethod("setInt", int.class, int.class);
                a = new Object[] {args[0], Boolean.TRUE.equals(args[1]) ? 1 : 0};
            } else if (m.getName().equals("setObject") && args[1] instanceof Boolean b) {
                method = PreparedStatement.class.getMethod("setInt", int.class, int.class);
                a = new Object[] {args[0], b ? 1 : 0};
            }
            Object value = method.getName().equals("setNull") ? null : a[1];
            if (original >= 1 && original <= bound.length) {
                bound[original - 1] = value;
            }
            if (!t.remapped()) {
                return DialectDataSource.invoke(target, method, a);
            }
            int[] map = t.paramMap();
            Object result = null;
            for (int j = 0; j < map.length; j++) {
                if (map[j] == original) {
                    Object[] a2 = a.clone();
                    a2[0] = j + 1;
                    result = DialectDataSource.invoke(target, method, a2);
                }
            }
            return result;
        }
    }
}
