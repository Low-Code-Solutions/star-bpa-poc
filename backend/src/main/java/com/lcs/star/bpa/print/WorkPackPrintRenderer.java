package com.lcs.star.bpa.print;

import java.time.Instant;
import java.time.ZoneId;

/**
 * BPA-10 (test-first stub, replaced when the renderer is built): renders nothing, which is the expected red state for the BPA10_
 * tests and the renderer's unit test.
 */
public class WorkPackPrintRenderer {

    public WorkPackPrintRenderer(ZoneId businessZone) {
    }

    public byte[] render(PrintData data, Instant at) {
        return new byte[0];
    }
}
