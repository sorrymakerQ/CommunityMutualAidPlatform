import java.sql.*;

/**
 * 验证 JDBC 下 UPDATE 的返回值语义：
 *   「匹配行数（found）」 vs 「实际改变行数（changed）」
 *
 * 用你项目 application.yml 里的连接串（不含 useAffectedRows）与显式开启后的行为做对比。
 */
public class AffectedRowsProbe {

    private static final String BASE =
            "jdbc:mysql://localhost:3306/linlibang?useSSL=false&serverTimezone=Asia/Shanghai"
                    + "&characterEncoding=utf-8&allowPublicKeyRetrieval=true";

    public static void main(String[] args) throws Exception {
        run("【你项目的配置】无 useAffectedRows（Connector/J 默认 false）", BASE);
        run("【显式 useAffectedRows=true】", BASE + "&useAffectedRows=true");
    }

    private static void run(String label, String url) throws Exception {
        System.out.println("========== " + label + " ==========");
        try (Connection c = DriverManager.getConnection(url, "root", "1234")) {
            // 场景 1：正常支付（行处于 status=0，WHERE 匹配且真实改动）
            reset(c, 0);
            System.out.println("  场景1 正常支付(status=0 -> 1)        executeUpdate = " + markPaid(c));

            // 场景 2：重复通知（行已是 status=1，WHERE 不匹配）
            reset(c, 1);
            System.out.println("  场景2 重复通知(status 已是 1)        executeUpdate = " + markPaid(c));

            // 场景 3：关键 —— UPDATE 成与数据库完全相同的值
            reset(c, 1);
            System.out.println("  场景3 值完全相同(只有 status=1)     executeUpdate = " + sameValue(c));

            // 场景 4：值看起来没变，但带 version = version + 1
            reset(c, 1);
            System.out.println("  场景4 值相同但带 version+1          executeUpdate = " + sameValueWithVersion(c));
        }
        System.out.println();
    }

    /** 每次重置成一行数据；initialStatus 为初始 status */
    private static void reset(Connection c, int initialStatus) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS tmp_affected_demo");
            st.execute("CREATE TABLE tmp_affected_demo ("
                    + "id BIGINT PRIMARY KEY, status TINYINT, version INT, "
                    + "update_time DATETIME, is_deleted TINYINT"
                    + ") ENGINE=InnoDB");
            st.execute("INSERT INTO tmp_affected_demo VALUES (1, " + initialStatus
                    + ", 0, '2020-01-01 00:00:00', 0)");
        }
    }

    /** 你项目 HelpRequestMapper.markPaid 的形式 */
    private static int markPaid(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE tmp_affected_demo SET status = 1, version = version + 1, update_time = NOW() "
                        + "WHERE id = ? AND status = 0 AND is_deleted = 0")) {
            ps.setLong(1, 1L);
            return ps.executeUpdate();
        }
    }

    /** 纯粹的「更新成相同值」：status 本来就是 1，再 set status=1，没有任何列发生变化 */
    private static int sameValue(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE tmp_affected_demo SET status = 1 WHERE id = ?")) {
            ps.setLong(1, 1L);
            return ps.executeUpdate();
        }
    }

    /** 值看似没变，但 version = version + 1 让这一行确实被改写 */
    private static int sameValueWithVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE tmp_affected_demo SET status = 1, version = version + 1 WHERE id = ?")) {
            ps.setLong(1, 1L);
            return ps.executeUpdate();
        }
    }
}
