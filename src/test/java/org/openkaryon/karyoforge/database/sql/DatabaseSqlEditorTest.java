package org.openkaryon.karyoforge.database.sql;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public final class DatabaseSqlEditorTest extends BasePlatformTestCase {
    public void testSqlFileProvidesHighlightedTokensAndKeywordCompletion() {
        var file = myFixture.configureByText("query.sql", "select /* WHERE */ 'JOIN', $tag$FROM$tag$, 22.4e-9 from [table]");
        assertSame(DatabaseSqlLanguage.INSTANCE, file.getLanguage());
        var highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage(), getProject(), file.getVirtualFile());
        var lexer = highlighter.getHighlightingLexer();
        lexer.start(file.getText());
        var types = new ArrayList<IElementType>();
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() != TokenType.WHITE_SPACE) types.add(lexer.getTokenType());
            lexer.advance();
        }
        assertEquals(List.of(DatabaseSqlLexer.KEYWORD, DatabaseSqlLexer.COMMENT, DatabaseSqlLexer.STRING,
                DatabaseSqlLexer.SYMBOL, DatabaseSqlLexer.STRING, DatabaseSqlLexer.SYMBOL, DatabaseSqlLexer.NUMBER,
                DatabaseSqlLexer.KEYWORD, DatabaseSqlLexer.NAME), types);
        assertEquals(DefaultLanguageHighlighterColors.KEYWORD, highlighter.getTokenHighlights(types.getFirst())[0]);
        myFixture.configureByText("keywords.sql", "sel<caret>");
        var completion = myFixture.completeBasic();
        if (completion == null) assertEquals("SELECT", myFixture.getEditor().getDocument().getText());
        else assertContainsElements(myFixture.getLookupElementStrings(), "SELECT");
    }
}
