package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.Disposable;
import org.openkaryon.karyoforge.notebook.runtime.LocalNotebookKernel;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Arrays;

/** Nonmodal input is transient UI state, never an editor document or saved transcript. */
final class NotebookInputPanel extends JPanel implements Disposable {
    private final JTextArea prompt = new JTextArea();
    private final JPasswordField value = new JPasswordField(28);
    private final JButton submit = new JButton("Submit input");
    private LocalNotebookKernel.InputRequest request;
    private final Runnable cancel;
    private boolean disposed;

    NotebookInputPanel(Runnable cancel) {
        super(new BorderLayout());
        this.cancel = cancel;
        prompt.setEditable(false);
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.getAccessibleContext().setAccessibleName("Notebook input prompt");
        value.getAccessibleContext().setAccessibleName("Notebook input value");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(value);
        actions.add(submit);
        var stop = new JButton("Cancel input");
        stop.addActionListener(event -> { clear(); this.cancel.run(); });
        actions.add(stop);
        submit.addActionListener(event -> submit());
        value.addActionListener(event -> submit());
        add(prompt, BorderLayout.NORTH);
        add(actions, BorderLayout.SOUTH);
        setVisible(false);
    }

    void showRequest(LocalNotebookKernel.InputRequest input) {
        clear();
        if (disposed || !input.isPending()) return;
        request = input;
        String text = input.prompt();
        prompt.setText(text.length() > 2048 ? text.substring(0, 2048) : text);
        value.setEchoChar(input.password() ? '\u2022' : (char) 0);
        value.setEnabled(true);
        submit.setEnabled(true);
        setVisible(true);
        revalidate();
        value.requestFocusInWindow();
    }

    private void submit() {
        LocalNotebookKernel.InputRequest input = request;
        if (input == null || !input.isPending()) { clear(); return; }
        char[] response = value.getPassword();
        value.setText("");
        if (response.length > 65_536) {
            Arrays.fill(response, (char) 0);
            prompt.setText("Input is limited to 65,536 characters. Enter a shorter response.");
            return;
        }
        value.setEnabled(false);
        submit.setEnabled(false);
        request = null;
        setVisible(false);
        Thread.ofVirtual().name("notebook-input-reply").start(() -> {
            try { input.reply(response); }
            catch (java.io.IOException ignored) {
                SwingUtilities.invokeLater(() -> { if (!disposed) cancel.run(); });
            }
            finally { Arrays.fill(response, (char) 0); }
        });
    }

    void clear() {
        request = null;
        value.setText("");
        prompt.setText("");
        submit.setEnabled(false);
        setVisible(false);
    }

    @Override public void dispose() { disposed = true; clear(); }
}
