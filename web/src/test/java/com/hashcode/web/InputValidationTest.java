package com.hashcode.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class InputValidationTest {
    @TempDir Path directory;

    private Path input(String value) throws IOException {
        Path path = directory.resolve("test.in");
        Files.writeString(path, value);
        return path;
    }

    @Test void acceptsDisconnectedEndpointsAndRepeatedDemands() throws IOException {
        SessionController.validateInput(input("1 1 2 1 10\n6\n1000 0\n0 0 7\n0 0 10\n"));
    }

    @Test void rejectsDangerousDimensionsBeforeAllocation() throws IOException {
        assertThrows(IOException.class, () -> SessionController.validateInput(input("2147483647 1 1 1 10")));
    }

    @Test void rejectsDuplicateLinksAndTrailingData() throws IOException {
        assertThrows(IOException.class, () -> SessionController.validateInput(input(
                "1 1 1 1 10\n6\n1000 2\n0 100\n0 200\n0 0 7\n")));
        assertThrows(IOException.class, () -> SessionController.validateInput(input(
                "1 1 1 1 10\n6\n1000 0\n0 0 7\nextra")));
    }
}
