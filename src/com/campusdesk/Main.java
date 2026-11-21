package com.campusdesk;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        ExamStore store = new ExamStore("campusdesk_exam.db");
        HttpServer server = HttpServer.create(new InetSocketAddress(8088), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", Files.readString(Path.of("web/index.html"))));
        server.createContext("/api/state", ex -> {
            try { send(ex, 200, "application/json", store.getDashboardStateJson()); }
            catch (Exception e) { send(ex, 500, "application/json", "{\"ok\":false}"); }
        });
        server.createContext("/api/students", ex -> {
            try {
                Map<String, String> b = JsonUtil.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(ex, 200, "application/json", store.registerStudent(b.get("regNo"), b.get("studentName"), b.get("department"), b.get("subjectCode"), b.get("subjectName")));
            } catch (Exception e) { send(ex, 400, "application/json", "{\"ok\":false}"); }
        });
        server.createContext("/api/allocate", ex -> {
            try { send(ex, 200, "application/json", store.allocateSeats()); }
            catch (Exception e) { send(ex, 500, "application/json", "{\"ok\":false}"); }
        });
        server.createContext("/api/attendance", ex -> {
            try {
                Map<String, String> b = JsonUtil.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(ex, 200, "application/json", store.markAttendance(b.get("regNo"), b.get("status"), b.get("remarks")));
            } catch (Exception e) { send(ex, 400, "application/json", "{\"ok\":false}"); }
        });
        server.start();
        System.out.println("CampusDesk running at http://127.0.0.1:8088");
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
