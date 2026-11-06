package com.lcs.star.bpa.assignment;

import java.util.List;

/**
 * BPA-12 (test-first stub, replaced when the reader is built): reads nothing, which is the expected red state for the BPA12_ tests
 * and the reader's unit test.
 */
public final class AssignmentFileReader {

    private AssignmentFileReader() {
    }

    /**
     * @param fileOutcome the file-level line ("File has no rows", ...), or null when the rows were read
     * @param rows the data rows, in file order
     */
    public record Result(String fileOutcome, List<Row> rows) {
    }

    /** One data row as written in the file: raw text, never parsed here (data model §5.2). rowNumber is the spreadsheet row, 1-based. */
    public record Row(int rowNumber, String workpackNumber, String badgeNo, String estimatedHours, String remarks) {
    }

    public static Result read(byte[] file) {
        return new Result("File cannot be read", List.of());
    }
}
