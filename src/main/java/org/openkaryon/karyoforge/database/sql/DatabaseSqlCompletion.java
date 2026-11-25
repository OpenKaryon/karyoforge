package org.openkaryon.karyoforge.database.sql;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.database.ui.DatabaseEditorBridge;

public final class DatabaseSqlCompletion extends CompletionContributor {
    @Override public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        var position = parameters.getPosition();
        if (isLiteral(position)) return;
        var matches = result.caseInsensitive();
        for (String keyword : DatabaseSqlLexer.KEYWORDS) matches.addElement(LookupElementBuilder.create(keyword).bold());
        var file = parameters.getOriginalFile();
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isWritable()) return;
        var catalog = file.getProject().getService(DatabaseEditorBridge.class).catalogFor(virtualFile);
        if (catalog.objects().isEmpty()) return;
        for (var suggestion : DatabaseSqlReferences.complete(file.getText(), parameters.getOffset(), catalog)) {
            matches.addElement(LookupElementBuilder.create(suggestion.insertion()).withLookupString(suggestion.lookup())
                    .withTypeText(suggestion.detail(), true));
        }
    }

    private static boolean isLiteral(PsiElement position) {
        var type = position.getNode().getElementType();
        if (type == DatabaseSqlLexer.COMMENT || type == DatabaseSqlLexer.STRING) return true;
        String name = position.getText();
        return !name.isEmpty() && (name.charAt(0) == '"' || name.charAt(0) == '`' || name.charAt(0) == '[');
    }
}
