package org.openkaryon.karyoforge.languages.python;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.toml.lang.psi.TomlFile;

import java.nio.file.Path;
import java.util.List;

public final class ProjectMetadataIntegrationTest extends BasePlatformTestCase {
    public void testStandardMetadataOffersOnlyDeclaredExtrasAndOneEscapedTargetArgument() {
        var metadata = metadata("""
                [build-system]
                requires = []
                build-backend = "local_backend"
                [project]
                name = "example-project"
                version = "1.0.0"
                [project.optional-dependencies]
                validation = ["pytest"]
                "data-tools" = []
                """
        );
        assertEquals("example-project", metadata.name());
        assertEquals(List.of("data-tools", "validation"), metadata.extras());
        var arguments = metadata.arguments(true, List.of("validation"), true);
        assertTrue(arguments.contains("--editable"));
        assertTrue(arguments.getLast(), arguments.getLast().contains("%20"));
        assertTrue(arguments.getLast().endsWith("[validation]"));
        assertFalse(arguments.contains("--no-build-isolation"));
        assertTrue(metadata.arguments(false, List.of(), false).contains("--no-build-isolation"));
        try {
            metadata.arguments(true, List.of("undeclared"), true);
            fail("Unknown extras must not reach pip");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    public void testInlineAndDottedTomlKeysUseTheNativeParser() {
        var inline = metadata("project = { name = 'inline-project', optional-dependencies = { checks = [] } }\n");
        assertEquals("inline-project", inline.name());
        assertEquals(List.of("checks"), inline.extras());
        var dotted = metadata("project.name = 'dotted-project'\nproject.optional-dependencies.checks = []\n");
        assertEquals("dotted-project", dotted.name());
        assertEquals(List.of("checks"), dotted.extras());
    }

    public void testToolOnlyMetadataAndProjectWordsInsideStringsAreRejected() {
        for (String source : List.of("[tool.ruff]\nline-length = 100\n", """
                [tool.example]
                description = """ + "\"\"\"\n[project]\nname = 'not-metadata'\n\"\"\"\n")) {
            try {
                metadata(source);
                fail("Tool configuration must not be presented as an installable project");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("no standard Python"));
            }
        }
    }

    public void testBuildSystemOnlyProjectsAreRecognizedAndMalformedTomlIsRejected() {
        assertTrue(metadata("[build-system]\nrequires = []\nbuild-backend = 'local_backend'\n").extras().isEmpty());
        try {
            metadata("[project]\nname = 'broken\n");
            fail("Malformed TOML must fail before an install action");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("syntax"));
        }
    }

    private StandardPythonProject metadata(String source) {
        var file = myFixture.configureByText("pyproject.toml", source);
        assertInstanceOf(file, TomlFile.class);
        return StandardPythonProject.inspect(Path.of(System.getProperty("java.io.tmpdir"), "Python project with spaces"), (TomlFile) file);
    }
}
