package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.psi.PyFile;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.sdk.PythonSdkType;

import java.nio.file.Files;
import java.util.Arrays;

public final class PythonEditorIntegrationTest extends BasePlatformTestCase {
    private static final LightProjectDescriptor PROJECT = new LightProjectDescriptor() {};
    private Sdk originalModuleSdk;
    private Sdk originalProjectSdk;

    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return PROJECT;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalModuleSdk = ModuleRootManager.getInstance(getModule()).getSdk();
        originalProjectSdk = ProjectRootManager.getInstance(getProject()).getProjectSdk();
        ModuleRootModificationUtil.setModuleSdk(getModule(), null);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            WriteAction.run(() -> {
                ModuleRootModificationUtil.setModuleSdk(getModule(), originalModuleSdk);
                ProjectRootManager.getInstance(getProject()).setProjectSdk(originalProjectSdk);
            });
        } finally {
            super.tearDown();
        }
    }

    public void testNativePythonParsingAndColoredTokens() {
        myFixture.configureByText("tokens.py", "def answer():\n    return 42\n");
        assertInstanceOf(myFixture.getFile(), PyFile.class);
        var highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
                PythonFileType.INSTANCE, getProject(), myFixture.getFile().getVirtualFile());
        assertNotNull(highlighter);
        var lexer = highlighter.getHighlightingLexer();
        lexer.start("def answer(): return 42");
        assertTrue(highlighter.getTokenHighlights(lexer.getTokenType()).length > 0);
    }

    public void testNativeCrossFileNavigation() {
        var definition = myFixture.addFileToProject("helpers.py", "def increment(value):\n    return value + 1\n");
        String source = "from helpers import increment\nanswer = increment(41)\n";
        var usage = myFixture.addFileToProject("main.py", source);
        myFixture.configureFromExistingVirtualFile(usage.getVirtualFile());
        var reference = usage.findReferenceAt(source.lastIndexOf("increment") + 1);
        assertNotNull(reference);
        var target = reference.resolve();
        assertInstanceOf(target, PyFunction.class);
        assertEquals("increment", ((PyFunction) target).getName());
        assertEquals(definition, target.getContainingFile());
    }

    public void testNativeCompletionAndFormatting() {
        myFixture.configureByText("complete.py", "def local_answer():\n    return 42\n\nlocal_ans<caret>");
        var options = myFixture.completeBasic();
        assertTrue(options == null ? myFixture.getFile().getText().substring(
                        myFixture.getFile().getText().lastIndexOf("\n\n") + 2).startsWith("local_answer")
                : Arrays.stream(options).anyMatch(option -> "local_answer".equals(option.getLookupString())));
        myFixture.configureByText("format.py", "def double( value ):\n return value*2\n");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile());
        });
        assertEquals("def double(value):\n    return value * 2\n", myFixture.getFile().getText());
    }

    public void testNotificationOwnsOnlyPythonFilesAndKeepsInvalidSdkWarnings() throws Exception {
        var file = myFixture.addFileToProject("unconfigured.py", "answer = 42\n");
        assertSame(getModule(), PythonInterpreters.owner(getProject(), file.getVirtualFile()));
        var notification = new PythonInterpreterNotification();
        assertNotNull(notification.collectNotificationData(getProject(), file.getVirtualFile()));
        var suppression = new MissingPythonInterpreterSuppressor();
        var element = file.findElementAt(0);
        assertNotNull(element);
        assertTrue(suppression.isSuppressedFor(element, "PyInterpreterInspection"));
        assertFalse(suppression.isSuppressedFor(element, "PyUnresolvedReferences"));
        var text = myFixture.addFileToProject("notes.txt", "text");
        assertNull(notification.collectNotificationData(getProject(), text.getVirtualFile()));
        var missing = Files.createTempDirectory("forge-sdk-").resolve("missing-python");
        PythonInterpreters.assign(getModule(), sdk("Unready Python", PythonSdkType.getInstance(), missing.toString(), null));
        assertEquals(PythonInterpreters.Status.UNREADY, PythonInterpreters.status(getModule()));
        assertNotNull(notification.collectNotificationData(getProject(), file.getVirtualFile()));
        assertFalse(suppression.isSuppressedFor(element, "PyInterpreterInspection"));
    }

    private Sdk sdk(String name, com.intellij.openapi.projectRoots.SdkType type, String home, String version) {
        var sdk = ProjectJdkTable.getInstance().createSdk(name + getName(), type);
        WriteAction.run(() -> {
            var editor = sdk.getSdkModificator();
            editor.setHomePath(home);
            editor.setVersionString(version);
            editor.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        return sdk;
    }
}
