package com.campusdesk;

import java.nio.file.Files;
import java.nio.file.Path;

public class CampusDeskTest {
    public static void main(String[] args) throws Exception {
        Path tmpDb = Files.createTempFile("campusdesk_test_", ".db");
        tmpDb.toFile().deleteOnExit();
        try (ExamStore store = new ExamStore(tmpDb.toString())) {
            assert store.countAdjacencyConflicts() == 0 : "Must yield 0 adjacent same-subject seats";
            assert store.registerStudent("RA25110030999", "Bharath Kumar", "CSE", "CS201", "Data Structures").contains("\"ok\":true");
            assert store.countAdjacencyConflicts() == 0 : "Conflicts must remain 0 after new registration";
            assert store.markAttendance("RA25110030999", "PRESENT", "ID verified").contains("\"ok\":true");
        }
        System.out.println("ALL CAMPUSDESK ASSERTION TESTS PASSED");
    }
}
