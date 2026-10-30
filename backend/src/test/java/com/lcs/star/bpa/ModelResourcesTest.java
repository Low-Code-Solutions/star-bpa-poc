package com.lcs.star.bpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** BPA-43 Part A (ADR 0003, L1): models and templates come from the single top-level folders, never from a copy in backend/. */
class ModelResourcesTest {

    private static final Path BACKEND = Path.of("").toAbsolutePath();

    @Test
    void BPA43_modelsAreReadFromTheTopLevelFolders() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();

        List<String> bpmn = names(resolver.getResources("classpath*:bpmn/*.bpmn"));
        assertThat(bpmn).containsExactlyInAnyOrder(
                "Process_PPC_DrawingRevisionNotice.bpmn",
                "Process_PPC_WorkPackAssignment.bpmn",
                "Process_PPC_WorkerReassignment.bpmn");
        assertThat(names(resolver.getResources("classpath*:dmn/*.dmn"))).containsExactly("Decision_WorkpackReleaseCheck.dmn");
    }

    @Test
    void BPA43_noModelOrTemplateIsCopiedIntoBackend() throws IOException {
        try (Stream<Path> files = Files.walk(BACKEND)) {
            List<String> copies = files
                    .filter(p -> !p.startsWith(BACKEND.resolve("target")))
                    .map(p -> BACKEND.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(p -> p.endsWith(".bpmn") || p.endsWith(".dmn") || p.endsWith(".xlsx") || p.startsWith("src/main/resources/templates"))
                    .toList();
            assertThat(copies).isEmpty();
        }
    }

    private static List<String> names(Resource[] resources) {
        return Arrays.stream(resources).map(Resource::getFilename).toList();
    }
}
