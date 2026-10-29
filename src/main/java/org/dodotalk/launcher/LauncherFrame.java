package org.dodotalk.launcher;

import org.dodotalk.Starter;
import org.dodotalk.core.SqueakVM;
import org.dodotalk.uilts.SqueakLogger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Small Swing launcher used when no image path is supplied on the command line.
 * <p>
 * The launcher itself does not include a Squeak image. The user selects an
 * image that they are licensed to use. The VM is deliberately started on a
 * worker thread: {@code SqueakVM.run()} eventually opens the Squeak display,
 * which performs its own Swing work on the EDT.
 */
public final class LauncherFrame extends JFrame {

    private static final String PREF_LAST_DIRECTORY = "lastImageDirectory";

    private final JTextField imageField = new JTextField(36);
    private final JButton browseButton = new JButton("Browse...");
    private final JButton startButton = new JButton("Start");
    private final JButton quitButton = new JButton("Quit");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel supportLabel = new JLabel(" ");

    private boolean busy;

    private LauncherFrame() {
        super("DodoTalk Launcher");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(560, 230));
        setContentPane(buildContent());
        pack();
        setLocationRelativeTo(null);
        updateStartState();
    }

    /**
     * Creates and shows the launcher on the Swing event dispatch thread.
     * The caller must ensure that the JVM is not headless.
     */
    public static void showLauncher() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Keep the cross-platform look and feel.
            }
            new LauncherFrame().setVisible(true);
        });
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel imageRow = new JPanel(new BorderLayout(8, 0));
        imageRow.add(new JLabel("Squeak image:"), BorderLayout.WEST);
        imageRow.add(imageField, BorderLayout.CENTER);
        imageRow.add(browseButton, BorderLayout.EAST);
        content.add(imageRow, BorderLayout.NORTH);

        JLabel notice = new JLabel("<html>"
                + "This launcher does not include a Squeak image.<br>"
                + "Select a gzip-compressed Squeak image (<code>*.image.gz</code>) "
                + "that you are licensed to use.<br>"
                + "Snapshots are written next to the selected image; the previous "
                + "image is kept as <code>.bak</code>."
                + "</html>");
        JPanel noticePanel = new JPanel(new BorderLayout(0, 6));
        noticePanel.add(notice, BorderLayout.NORTH);
        noticePanel.add(supportLabel, BorderLayout.CENTER);
        content.add(noticePanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(statusLabel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(startButton);
        actions.add(quitButton);
        bottom.add(actions, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        browseButton.addActionListener(event -> chooseImage());
        startButton.addActionListener(event -> startVm());
        quitButton.addActionListener(event -> quit());
        imageField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateStartState();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateStartState();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateStartState();
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                quit();
            }
        });
        getRootPane().setDefaultButton(startButton);
        return content;
    }

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser(initialDirectory());
        chooser.setDialogTitle("Select a Squeak image");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new SqueakImageFileFilter());

        String current = imageField.getText().trim();
        if (!current.isEmpty()) {
            File currentFile = new File(current);
            if (currentFile.isFile()) {
                chooser.setSelectedFile(currentFile);
            }
        }

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        imageField.setText(selected.getAbsolutePath());
        rememberDirectory(selected.getParentFile());
    }

    private void startVm() {
        File selected = new File(imageField.getText().trim());
        try {
            Starter.validateImageFile(selected);
        } catch (IOException | IllegalArgumentException e) {
            showError("Cannot use this image:\n" + messageOf(e));
            return;
        }

        setBusy(true);
        statusLabel.setText("Loading " + selected.getName() + "...");

        Thread worker = new Thread(() -> {
            try {
                SqueakVM vm = Starter.load(selected);
                SwingUtilities.invokeAndWait(() -> setVisible(false));
                vm.run();
            } catch (Throwable failure) {
                SqueakLogger.log_E("Failed to start DodoTalk", failure);
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    setVisible(true);
                    statusLabel.setText("Failed to start");
                    showError("Failed to start DodoTalk:\n" + messageOf(failure));
                });
            }
        }, "dodotalk-vm");
        worker.start();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        browseButton.setEnabled(!busy);
        imageField.setEnabled(!busy);
        updateStartState();
    }

    private void updateStartState() {
        startButton.setEnabled(!busy && !imageField.getText().trim().isEmpty());
        updateSupportFileState();
    }

    /**
     * Shows, before boot, whether the optional Squeak support files are
     * available. The VM can still start without them, but source browsing and
     * change logging will be unavailable.
     */
    private void updateSupportFileState() {
        String text = imageField.getText().trim();
        if (text.isEmpty()) {
            supportLabel.setText(" ");
            supportLabel.setToolTipText(null);
            return;
        }

        File selected = new File(text);
        if (!selected.isFile()) {
            supportLabel.setText(" ");
            supportLabel.setToolTipText(null);
            return;
        }

        List<String> warnings = Starter.supportFileWarnings(selected);
        if (warnings.isEmpty()) {
            supportLabel.setText("SqueakV2.sources and changes file found.");
            supportLabel.setToolTipText(null);
        } else {
            supportLabel.setText("Warning: optional Squeak support file(s) missing "
                    + "(hover for details).");
            supportLabel.setToolTipText(String.join("\n", warnings));
        }
    }

    private File initialDirectory() {
        try {
            String last = Preferences.userNodeForPackage(LauncherFrame.class)
                    .get(PREF_LAST_DIRECTORY, null);
            if (last != null && !last.isBlank()) {
                File directory = new File(last);
                if (directory.isDirectory()) {
                    return directory;
                }
            }
        } catch (SecurityException ignored) {
            // Preferences are optional.
        }

        File projectImages = new File(System.getProperty("user.dir"), "images");
        if (projectImages.isDirectory()) {
            return projectImages;
        }
        return new File(System.getProperty("user.dir"));
    }

    private void rememberDirectory(File directory) {
        if (directory == null) {
            return;
        }
        try {
            Preferences.userNodeForPackage(LauncherFrame.class)
                    .put(PREF_LAST_DIRECTORY, directory.getAbsolutePath());
        } catch (SecurityException ignored) {
            // Preferences are optional.
        }
    }

    private void quit() {
        dispose();
        System.exit(0);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "DodoTalk", JOptionPane.ERROR_MESSAGE);
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * Kept package-private for a small unit test of the accepted file names.
     */
    static boolean isSupportedImageName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".image.gz");
    }
}
