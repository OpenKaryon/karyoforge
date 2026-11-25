package org.openkaryon.karyoforge.database.sql;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DatabaseSqlHighlighter extends SyntaxHighlighterFactory {
    @Override public @NotNull SyntaxHighlighter getSyntaxHighlighter(@Nullable Project project, @Nullable VirtualFile file) {
        return new SyntaxHighlighterBase() {
            @Override public @NotNull Lexer getHighlightingLexer() { return new DatabaseSqlLexer(); }

            @Override public TextAttributesKey @NotNull [] getTokenHighlights(IElementType type) {
                if (type == DatabaseSqlLexer.KEYWORD) return pack(DefaultLanguageHighlighterColors.KEYWORD);
                if (type == DatabaseSqlLexer.STRING) return pack(DefaultLanguageHighlighterColors.STRING);
                if (type == DatabaseSqlLexer.COMMENT) return pack(DefaultLanguageHighlighterColors.BLOCK_COMMENT);
                if (type == DatabaseSqlLexer.NUMBER) return pack(DefaultLanguageHighlighterColors.NUMBER);
                return TextAttributesKey.EMPTY_ARRAY;
            }
        };
    }
}
