package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.DatabaseTimeouts;
import org.openkaryon.karyoforge.database.core.ResultLimits;

import javax.swing.JComponent;

final class DatabaseResultLimitsDialog extends DialogWrapper {
    private final JBTextField page;
    private final JBTextField rows;
    private final JBTextField bytes;
    private final JBTextField cell;
    private final JBTextField columns;
    private final JBTextField catalog;
    private final JBTextField connectionTimeout;
    private final JBTextField queryTimeout;
    private final int maximumQuerySeconds;

    DatabaseResultLimitsDialog(Project project, DatabaseSources.Profile profile) {
        super(project);
        var limits = profile.limits();
        connectionTimeout = number(profile.timeouts().connectSeconds());
        queryTimeout = number(profile.timeouts().querySeconds());
        maximumQuerySeconds = DatabaseTimeouts.maximumQuerySeconds(profile.source());
        page = number(limits.pageSize());
        rows = number(limits.maxRows());
        bytes = number(limits.maxBytes());
        cell = number(limits.maxCellChars());
        columns = number(limits.maxColumns());
        catalog = number(limits.catalogLimit());
        setTitle("Database Query Limits");
        setOKButtonText("Save");
        init();
    }

    private static JBTextField number(int value) { return new JBTextField(Integer.toString(value), 12); }

    private static int read(JBTextField field) { return Integer.parseInt(field.getText().strip()); }

    ResultLimits limits() { return new ResultLimits(read(page), read(rows), read(bytes), read(cell), read(columns), read(catalog)); }

    DatabaseTimeouts timeouts() {
        var timeouts = new DatabaseTimeouts(read(connectionTimeout), read(queryTimeout));
        if (timeouts.querySeconds() > maximumQuerySeconds) {
            throw new IllegalArgumentException("Enter a query timeout of at most " + maximumQuerySeconds + " seconds.");
        }
        return timeouts;
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Rows per page (1–1000)", page)
                .addLabeledComponent("Total rows (page size–100000)", rows)
                .addLabeledComponent("Detached result bytes (1024–67108864)", bytes)
                .addLabeledComponent("Characters per cell (1–1048576)", cell)
                .addLabeledComponent("Columns (1–1024)", columns)
                .addLabeledComponent("Catalog objects (1–10000)", catalog)
                .addLabeledComponent("Connection timeout in seconds (1–120)", connectionTimeout)
                .addLabeledComponent("Query timeout in seconds (1–" + maximumQuerySeconds + ")", queryTimeout)
                .getPanel();
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            limits();
            timeouts();
            return null;
        } catch (NumberFormatException invalid) {
            return new ValidationInfo("Enter whole numbers for the query limits.");
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage());
        }
    }
}
