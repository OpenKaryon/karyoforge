package org.openkaryon.karyoforge.database.sql;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/** Exposes SQL tokens to the editor. Execution validates the chosen engine's statement separately. */
public final class DatabaseSqlParser implements ParserDefinition {
    private static final IFileElementType FILE = new IFileElementType(DatabaseSqlLanguage.INSTANCE);

    @Override public @NotNull Lexer createLexer(Project project) { return new DatabaseSqlLexer(); }
    @Override public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override public @NotNull TokenSet getCommentTokens() { return TokenSet.create(DatabaseSqlLexer.COMMENT); }
    @Override public @NotNull TokenSet getStringLiteralElements() { return TokenSet.create(DatabaseSqlLexer.STRING); }

    @Override public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            var file = builder.mark();
            while (!builder.eof()) builder.advanceLexer();
            file.done(root);
            return builder.getTreeBuilt();
        };
    }

    @Override public @NotNull PsiElement createElement(ASTNode node) {
        throw new IllegalArgumentException("Unexpected composite SQL element: " + node.getElementType());
    }

    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider provider) {
        return new PsiFileBase(provider, DatabaseSqlLanguage.INSTANCE) {
            @Override public @NotNull FileType getFileType() { return DatabaseSqlFileType.INSTANCE; }
        };
    }
}
