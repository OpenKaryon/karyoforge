package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CreationIntegrationTest extends BasePlatformTestCase {
    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    public void testExistingDirectoriesAreRejectedWithoutChangingTheirFiles() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Path base = Files.writeString(root.resolve("base-python"), "validation does not execute this file");
        Path target = Files.createDirectory(root.resolve(".venv"));
        Path userFile = Files.writeString(target.resolve("keep.txt"), "user content");
        var previous = ModuleRootManager.getInstance(getModule()).getSdk();
        try {
            VirtualEnvironmentCreation.validate(base, target);
            fail("An existing target must never be reused or overwritten");
        } catch (IllegalArgumentException expected) {
            assertEquals("user content", Files.readString(userFile));
            assertSame(previous, ModuleRootManager.getInstance(getModule()).getSdk());
        }
    }

    public void testMissingParentAndRelativePathsCannotStartCreation() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Path base = Files.writeString(root.resolve("base-python"), "validation only");
        for (Path target : new Path[]{Path.of("relative/.venv"), root.resolve("missing/.venv")}) {
            try {
                VirtualEnvironmentCreation.validate(base, target);
                fail("Creation requires an explicit absolute target with an existing parent");
            } catch (IllegalArgumentException expected) {
                assertFalse(Files.exists(root.resolve("missing")));
            }
        }
        assertNotNull(ActionManager.getInstance().getAction("org.openkaryon.karyoforge.python.createEnvironment"));
    }
}
