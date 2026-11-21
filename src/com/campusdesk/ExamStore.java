package com.campusdesk;

import java.sql.*;
import java.util.*;

public class ExamStore implements AutoCloseable {
    private final Connection conn;
    private record StudentRow(String regNo, String name, String dept, String subjectCode, String subjectName) {}
    private record HallRow(String hallCode, String hallName, int rows, int cols, String invigilator) {}

    public ExamStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        seedInitialData();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS exam_halls (
                    hall_code TEXT PRIMARY KEY,
                    hall_name TEXT NOT NULL,
                    rows_count INTEGER NOT NULL,
                    cols_count INTEGER NOT NULL,
                    invigilator_name TEXT NOT NULL
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS students (
                    reg_no TEXT PRIMARY KEY,
                    student_name TEXT NOT NULL,
                    department TEXT NOT NULL,
                    subject_code TEXT NOT NULL,
                    subject_name TEXT NOT NULL,
                    assigned_hall TEXT DEFAULT '',
                    seat_row INTEGER DEFAULT 0,
                    seat_col INTEGER DEFAULT 0,
                    seat_label TEXT DEFAULT '',
                    attendance_status TEXT NOT NULL DEFAULT 'UNMARKED',
                    remarks TEXT DEFAULT ''
                )
            """);
        }
    }

    private void seedInitialData() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM exam_halls")) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO exam_halls VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "HALL-A"); ps.setString(2, "Tech Park Hall 401"); ps.setInt(3, 4); ps.setInt(4, 5); ps.setString(5, "Dr. R. Srinivasan"); ps.addBatch();
            ps.setString(1, "HALL-B"); ps.setString(2, "University Block 202"); ps.setInt(3, 4); ps.setInt(4, 5); ps.setString(5, "Prof. K. Meenakshi"); ps.addBatch();
            ps.executeBatch();
        }

        Object[][] candidates = {
            {"RA25110030101", "Aarav Sharma", "CSE", "CS201", "Data Structures & Algorithms"},
            {"RA25110030102", "Priya Darshini", "CSE", "CS201", "Data Structures & Algorithms"},
            {"RA25110030103", "Vikram Reddy", "CSE", "CS201", "Data Structures & Algorithms"},
            {"RA25110030104", "Saai Kirutthk", "CSE", "CS201", "Data Structures & Algorithms"},
            {"RA25110030201", "Divya Bharathi", "AI-DS", "MA201", "Transform & Boundary Maths"},
            {"RA25110030202", "Rohan Kulkarni", "AI-DS", "MA201", "Transform & Boundary Maths"},
            {"RA25110030203", "Sneha Menon", "AI-DS", "MA201", "Transform & Boundary Maths"},
            {"RA25110030204", "Pranav Venkatesh", "AI-DS", "MA201", "Transform & Boundary Maths"},
            {"RA25110030301", "Nikhil Varma", "IT", "CS202", "Computer Organisation & Arch"},
            {"RA25110030302", "Shruti Iyer", "IT", "CS202", "Computer Organisation & Arch"},
            {"RA25110030303", "Aditya Menon", "IT", "CS202", "Computer Organisation & Arch"},
            {"RA25110030304", "Lakshmi Narayanan", "IT", "CS202", "Computer Organisation & Arch"},
            {"RA25110030401", "Siddharth Rao", "ECE", "EC204", "Digital Logic & Microprocessors"},
            {"RA25110030402", "Nandini Pillai", "ECE", "EC204", "Digital Logic & Microprocessors"},
            {"RA25110030403", "Gokul Nath", "ECE", "EC204", "Digital Logic & Microprocessors"},
            {"RA25110030404", "Kavya Ramesh", "ECE", "EC204", "Digital Logic & Microprocessors"}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO students (reg_no, student_name, department, subject_code, subject_name) VALUES (?, ?, ?, ?, ?)")) {
            for (Object[] c : candidates) {
                ps.setString(1, (String) c[0]); ps.setString(2, (String) c[1]); ps.setString(3, (String) c[2]);
                ps.setString(4, (String) c[3]); ps.setString(5, (String) c[4]); ps.addBatch();
            }
            ps.executeBatch();
        }
        allocateSeats();
        markAttendance("RA25110030101", "PRESENT", "Hall ticket verified");
        markAttendance("RA25110030402", "ABSENT", "Absent at roll call");
    }

    public synchronized String registerStudent(String rawRegNo, String name, String dept, String rawSubCode, String subName) throws SQLException {
        String regNo = rawRegNo == null ? "" : rawRegNo.trim().toUpperCase();
        String subCode = rawSubCode == null ? "" : rawSubCode.trim().toUpperCase();
        if (regNo.isEmpty() || name == null || name.isBlank() || subCode.isEmpty()) {
            return "{\"ok\":false,\"error\":\"Register number, student name, and subject code are required\"}";
        }
        try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM students WHERE reg_no = ?")) {
            check.setString(1, regNo);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) return "{\"ok\":false,\"error\":\"Register number already exists\"}";
            }
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO students (reg_no, student_name, department, subject_code, subject_name) VALUES (?, ?, ?, ?, ?)")) {
            ins.setString(1, regNo); ins.setString(2, name.trim());
            ins.setString(3, (dept == null || dept.isBlank()) ? "CSE" : dept.trim().toUpperCase());
            ins.setString(4, subCode); ins.setString(5, (subName == null || subName.isBlank()) ? subCode : subName.trim());
            ins.executeUpdate();
        }
        allocateSeats();
        return "{\"ok\":true,\"regNo\":\"" + JsonUtil.escape(regNo) + "\"}";
    }

    public synchronized String allocateSeats() throws SQLException {
        conn.setAutoCommit(false);
        try {
            List<HallRow> halls = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM exam_halls ORDER BY hall_code ASC")) {
                while (rs.next()) halls.add(new HallRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5)));
            }
            Map<String, Deque<StudentRow>> buckets = new LinkedHashMap<>();
            int total = 0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM students ORDER BY subject_code ASC, reg_no ASC")) {
                while (rs.next()) {
                    total++;
                    String sc = rs.getString("subject_code");
                    buckets.computeIfAbsent(sc, k -> new ArrayDeque<>()).addLast(
                        new StudentRow(rs.getString("reg_no"), rs.getString("student_name"), rs.getString("department"), sc, rs.getString("subject_name")));
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE students SET assigned_hall = '', seat_row = 0, seat_col = 0, seat_label = ''");
            }
            int placed = 0;
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE students SET assigned_hall = ?, seat_row = ?, seat_col = ?, seat_label = ? WHERE reg_no = ?")) {
                for (HallRow hall : halls) {
                    String[][] grid = new String[hall.rows() + 1][hall.cols() + 1];
                    for (int r = 1; r <= hall.rows(); r++) {
                        for (int c = 1; c <= hall.cols(); c++) {
                            if (placed >= total) break;
                            String left = c > 1 ? grid[r][c - 1] : null;
                            String top = r > 1 ? grid[r - 1][c] : null;
                            Deque<StudentRow> chosen = buckets.values().stream()
                                .filter(q -> !q.isEmpty() && !q.peekFirst().subjectCode().equals(left) && !q.peekFirst().subjectCode().equals(top))
                                .max(Comparator.comparingInt(Deque::size))
                                .orElseGet(() -> buckets.values().stream().filter(q -> !q.isEmpty()).max(Comparator.comparingInt(Deque::size)).orElse(null));
                            if (chosen == null || chosen.isEmpty()) break;
                            StudentRow s = chosen.removeFirst();
                            grid[r][c] = s.subjectCode();
                            upd.setString(1, hall.hallCode()); upd.setInt(2, r); upd.setInt(3, c);
                            upd.setString(4, hall.hallCode().replace("HALL-", "") + "-R" + r + "C" + c);
                            upd.setString(5, s.regNo()); upd.addBatch();
                            placed++;
                        }
                    }
                }
                upd.executeBatch();
            }
            conn.commit();
            return String.format(Locale.US, "{\"ok\":true,\"allocated\":%d,\"adjacencyConflicts\":%d}", placed, countAdjacencyConflicts());
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized int countAdjacencyConflicts() throws SQLException {
        Map<String, String> map = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT assigned_hall, seat_row, seat_col, subject_code FROM students WHERE assigned_hall <> ''")) {
            while (rs.next()) map.put(rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getInt(3), rs.getString(4));
        }
        int conflicts = 0;
        for (var e : map.entrySet()) {
            String[] p = e.getKey().split(":");
            int r = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]);
            if (e.getValue().equals(map.get(p[0] + ":" + r + ":" + (c + 1)))) conflicts++;
            if (e.getValue().equals(map.get(p[0] + ":" + (r + 1) + ":" + c))) conflicts++;
        }
        return conflicts;
    }

    public synchronized String markAttendance(String rawRegNo, String rawStatus, String remarks) throws SQLException {
        String regNo = rawRegNo == null ? "" : rawRegNo.trim().toUpperCase();
        String status = rawStatus == null ? "PRESENT" : rawStatus.trim().toUpperCase();
        try (PreparedStatement ps = conn.prepareStatement("UPDATE students SET attendance_status = ?, remarks = ? WHERE reg_no = ?")) {
            ps.setString(1, status); ps.setString(2, remarks == null ? "" : remarks.trim()); ps.setString(3, regNo);
            return ps.executeUpdate() == 0 ? "{\"ok\":false,\"error\":\"Student not found\"}" : "{\"ok\":true,\"status\":\"" + status + "\"}";
        }
    }

    public synchronized String getDashboardStateJson() throws SQLException {
        int total = 0, alloc = 0, pres = 0, abs = 0, mal = 0;
        List<String> students = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM students ORDER BY assigned_hall, seat_row, seat_col")) {
            while (rs.next()) {
                total++;
                String hall = rs.getString("assigned_hall"), att = rs.getString("attendance_status");
                if (!hall.isEmpty()) alloc++;
                if ("PRESENT".equals(att)) pres++; else if ("ABSENT".equals(att)) abs++; else if ("MALPRACTICE".equals(att)) mal++;
                students.add(String.format(Locale.US,
                    "{\"regNo\":\"%s\",\"name\":\"%s\",\"department\":\"%s\",\"subjectCode\":\"%s\",\"subjectName\":\"%s\",\"hallCode\":\"%s\",\"row\":%d,\"col\":%d,\"seatLabel\":\"%s\",\"status\":\"%s\",\"remarks\":\"%s\"}",
                    JsonUtil.escape(rs.getString("reg_no")), JsonUtil.escape(rs.getString("student_name")), JsonUtil.escape(rs.getString("department")),
                    JsonUtil.escape(rs.getString("subject_code")), JsonUtil.escape(rs.getString("subject_name")), JsonUtil.escape(hall),
                    rs.getInt("seat_row"), rs.getInt("seat_col"), JsonUtil.escape(rs.getString("seat_label")), JsonUtil.escape(att), JsonUtil.escape(rs.getString("remarks"))));
            }
        }
        List<String> halls = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM exam_halls ORDER BY hall_code")) {
            while (rs.next()) {
                halls.add(String.format(Locale.US, "{\"hallCode\":\"%s\",\"hallName\":\"%s\",\"rows\":%d,\"cols\":%d,\"invigilator\":\"%s\"}",
                    JsonUtil.escape(rs.getString(1)), JsonUtil.escape(rs.getString(2)), rs.getInt(3), rs.getInt(4), JsonUtil.escape(rs.getString(5))));
            }
        }
        return String.format(Locale.US,
            "{\"metrics\":{\"totalStudents\":%d,\"allocated\":%d,\"present\":%d,\"absent\":%d,\"malpractice\":%d,\"conflicts\":%d},\"halls\":[%s],\"students\":[%s]}",
            total, alloc, pres, abs, mal, countAdjacencyConflicts(), String.join(",", halls), String.join(",", students));
    }

    @Override public void close() throws SQLException { if (conn != null && !conn.isClosed()) conn.close(); }
}
