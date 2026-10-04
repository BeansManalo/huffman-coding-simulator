package hcs.gui.input;

import hcs.core.FrequencyScan;
import hcs.gui.MainFrame;
import hcs.gui.Theme;
import hcs.gui.dialog.SettingsDialog;
import hcs.gui.dialog.SettingsDialog.DisplayMode;
import java.awt.BorderLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.LayerUI;

/**
 * The input screen: two equal boxes side by side, one to type or paste the text to encode
 * and one to choose a file to encode. Filling one disables the other; Clear empties both.
 * Generate fills the editor with a paragraph of a novel, a different one each time.
 * Submit hands the input over, with the chosen display mode, to whoever made the panel.
 */
@SuppressWarnings("serial")
public class InputPanel extends JPanel {

    private static final String HINT = "Type or paste text here.";

    /** The biggest file the uploader accepts: 1 GB, counted the way Windows does (1024 x 1024 x 1024 bytes). */
    private static final long MAX_FILE_BYTES = 1L << 30;
    private static final String MAX_FILE_TEXT = "1 GB";

    private final JTextArea txtInput;
    private final JLabel lblFileName = new JLabel();
    private final JLabel lblFileInfo = new JLabel();
    private final JButton btnChoose = Theme.bar("CHOOSE FILE");
    private final JButton btnGenerate = Theme.bar("GENERATE");
    /** The novel paragraphs not shown yet, in the order they will be. Refilled, reshuffled, when empty. */
    private final List<String> deck = new ArrayList<>();
    private final JComponent editorBox;
    private final JComponent uploadBox;
    private final BiConsumer<FrequencyScan, DisplayMode> onSubmit;
    private File file;
    private DisplayMode displayMode = DisplayMode.ANIMATED;

    /**
     * Create the panel. {@code onSubmit} gets the input (not read yet) and the display mode.
     */
    public InputPanel(BiConsumer<FrequencyScan, DisplayMode> onSubmit) {
        this.onSubmit = onSubmit;
        setPreferredSize(MainFrame.WINDOW_SIZE);
        setBackground(Theme.CHROME_BG);
        setBorder(new EmptyBorder(24, 24, 24, 24));
        setLayout(new BorderLayout(0, 20));

        txtInput = new JTextArea() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getDocument().getLength() == 0) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(getFont());
                    g2.setColor(Theme.TEXT_MUTED);
                    g2.drawString(HINT, getInsets().left, getInsets().top + g2.getFontMetrics().getAscent());
                    g2.dispose();
                }
            }
        };
        txtInput.setFont(Theme.FONT_EDITOR);
        txtInput.setBackground(Theme.EDITOR_BG);
        txtInput.setForeground(Theme.TEXT_PRIMARY);
        txtInput.setCaretColor(Theme.CARET);
        txtInput.setSelectionColor(Theme.SELECTION);
        txtInput.setSelectedTextColor(Theme.TEXT_PRIMARY);
        txtInput.setBorder(new EmptyBorder(14, 16, 14, 16));
        txtInput.setLineWrap(true);
        txtInput.setWrapStyleWord(true);
        txtInput.setTabSize(4);
        // Typing the first character (or deleting the last) is what switches the other box on or off.
        txtInput.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });

        lblFileName.setFont(Theme.FONT_EDITOR_BOLD);
        lblFileName.setForeground(Theme.TEXT_PRIMARY);
        lblFileInfo.setFont(Theme.FONT_LABEL);
        lblFileInfo.setForeground(Theme.TEXT_MUTED);
        btnChoose.addActionListener(e -> choose());
        btnGenerate.addActionListener(e -> generate());
        JPanel upload = new JPanel(new GridBagLayout());
        upload.setOpaque(false);
        GridBagConstraints row = new GridBagConstraints();
        row.gridwidth = GridBagConstraints.REMAINDER;
        row.insets = new Insets(0, 0, 6, 0);
        upload.add(lblFileName, row);
        row.insets = new Insets(0, 0, 0, 0);
        upload.add(lblFileInfo, row);

        // Each box ends in a strip of its own with its own action, so the two sit on one line.
        JPanel editor = new JPanel(new BorderLayout());
        editor.setOpaque(false);
        editor.add(Theme.scroll(txtInput), BorderLayout.CENTER);
        editor.add(footer(btnGenerate), BorderLayout.SOUTH);
        JPanel uploader = new JPanel(new BorderLayout());
        uploader.setOpaque(false);
        uploader.add(upload, BorderLayout.CENTER);
        uploader.add(footer(btnChoose), BorderLayout.SOUTH);

        editorBox = box("TEXT EDITOR", "Disabled: a file is chosen. Press CLEAR to type.", editor);
        uploadBox = box("FILE UPLOAD", "Disabled: text is entered. Press CLEAR to upload.", uploader);

        // A one-row grid hands both boxes exactly the same size.
        JPanel boxes = new JPanel(new GridLayout(1, 2, 24, 0));
        boxes.setOpaque(false);
        boxes.add(editorBox);
        boxes.add(uploadBox);

        // Settings is about the app, so it stands apart on the left; what happens to the input is together on
        // the right, the main action last. The grid makes CLEAR and SUBMIT the same width.
        JButton btnClear = Theme.ghost("CLEAR");
        btnClear.addActionListener(e -> clear());
        JButton btnSettings = Theme.ghost("SETTINGS");
        btnSettings.addActionListener(e -> displayMode = SettingsDialog.show(this, displayMode));
        JButton btnSubmit = Theme.button("SUBMIT");
        btnSubmit.addActionListener(e -> submit());
        JPanel pair = new JPanel(new GridLayout(1, 2, 16, 0));
        pair.setOpaque(false);
        pair.add(btnClear);
        pair.add(btnSubmit);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.add(btnSettings, BorderLayout.WEST);
        actions.add(pair, BorderLayout.EAST);

        add(boxes, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        refresh();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Theme.backdrop(g, this);
        Theme.dots(g, this, 0, 0);
        Theme.halo(g, editorBox);
        Theme.halo(g, uploadBox);
    }

    /** The strip at the foot of a box: its action, across the full width, so the two boxes' strips match. */
    private static JPanel footer(JButton action) {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.DIVIDER), new EmptyBorder(6, 10, 6, 10)));
        footer.add(action, BorderLayout.CENTER);
        return footer;
    }

    /** A titled box (see Theme.box). While disabled it is dimmed and shows the note. */
    private static JComponent box(String title, String note, JComponent body) {
        // A JLayer, not a painted-over panel: it also repaints the dimming when something inside redraws.
        return new JLayer<>(Theme.box(title, body), new LayerUI<JPanel>() {
            @Override
            public void paint(Graphics g, JComponent c) {
                super.paint(g, c);
                if (!c.isEnabled()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Theme.VEIL);
                    g2.fillRect(0, 0, c.getWidth(), c.getHeight());
                    g2.setColor(Theme.DIVIDER);
                    g2.drawRect(0, 0, c.getWidth() - 1, c.getHeight() - 1);
                    g2.setFont(Theme.FONT_LABEL);
                    g2.setColor(Theme.TEXT_MUTED);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(note, (c.getWidth() - fm.stringWidth(note)) / 2,
                        (c.getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            }
        });
    }

    /** Brings the file labels up to date and switches each box off while the other one holds something. */
    private void refresh() {
        boolean hasText = txtInput.getDocument().getLength() > 0;
        boolean hasFile = file != null;
        lblFileName.setText(hasFile ? file.getName() : "No file chosen");
        lblFileInfo.setForeground(Theme.TEXT_MUTED);   // back from the red of a refused file
        lblFileInfo.setFont(Theme.FONT_LABEL);
        lblFileInfo.setText(hasFile ? String.format("%,d bytes", file.length())
            : "Text file by default, any file type works. Maximum size: " + MAX_FILE_TEXT);
        txtInput.setEnabled(!hasFile);
        editorBox.setEnabled(!hasFile);
        btnChoose.setEnabled(!hasText);
        uploadBox.setEnabled(!hasText);
        btnGenerate.setEnabled(!hasFile);
    }

    /** Choose File button: lets the user pick a file. It is not read yet, so any kind of file will do, up to the size limit. */
    private void choose() {
        JFileChooser chooser = new JFileChooser();
        // Only the starting filter; "All Files" is still in the chooser's drop-down.
        chooser.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File picked = chooser.getSelectedFile();
            if (picked.length() > MAX_FILE_BYTES) {   // only the size is asked for: nothing is read
                showProblem("That file is over the " + MAX_FILE_TEXT + " limit. Choose a smaller one.");
                return;
            }
            file = picked;
            refresh();
        }
    }

    /**
     * Generate button: puts a paragraph of a novel in the editor, replacing what is there. The
     * paragraphs are 6,334 from 44 public domain novels from Project Gutenberg, kept gzipped in
     * one file. They come in a shuffled order, so none shows twice before all have shown once.
     */
    private void generate() {
        if (deck.isEmpty()) {
            try (InputStream in = new GZIPInputStream(InputPanel.class.getResourceAsStream("/hcs/resources/paragraphs.txt.gz"))) {
                deck.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n\n")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Collections.shuffle(deck);
        }
        txtInput.setText(deck.removeLast());
        txtInput.setCaretPosition(0);
    }

    /** Submit button: hands over the typed text or the chosen file, or says what is missing. */
    private void submit() {
        FrequencyScan scan;
        if (file != null) {
            if (file.length() == 0) {
                showProblem("That file is empty. Choose another one.");
                return;
            }
            scan = new FrequencyScan(file);
        } else if (txtInput.getDocument().getLength() > 0) {
            scan = new FrequencyScan(txtInput.getText().getBytes(StandardCharsets.UTF_8));
        } else {
            showProblem("Nothing to encode yet. Type some text or choose a file.");
            return;
        }
        onSubmit.accept(scan, displayMode);
    }

    /** Says what is wrong in red, where the file's details are. It goes when the input changes. */
    private void showProblem(String text) {
        lblFileInfo.setForeground(Theme.ERROR);
        lblFileInfo.setFont(Theme.FONT_TITLE);
        lblFileInfo.setText(text);
    }

    /** Clear button: empties both boxes, so both are available again. */
    private void clear() {
        file = null;
        txtInput.setText("");
        refresh();
        txtInput.requestFocusInWindow();
    }
}
