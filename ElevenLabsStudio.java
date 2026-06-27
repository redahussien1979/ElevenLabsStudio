import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ElevenLabs Audio Studio (Swing GUI)
 * Java port of the Node.js ElevenLabs generator/transcriber. Zero external
 * dependencies (Java 11+). Built-in HTTP client + tiny JSON parser.
 */
public class ElevenLabsStudio extends JFrame {

    // ---- shared config widgets ----
    private final JTextField apiKeyField  = new JTextField("sk_085b309952bcc3227379faa49e8f49d40478fda3985840e7");
    private final JTextField workDirField = new JTextField(System.getProperty("user.dir"));
    private final JPanel     linesContainer = new JPanel();
    private final List<LineRow> lineRows    = new ArrayList<>();
    private final JTextArea  logArea       = new JTextArea();

    // ---- TTS (option 1) settings ----
    private final JComboBox<String> ttsVoice = voiceCombo("TX3LPaxmHKxFdv7VOQHJ"); // Liam — default for new lines
    private final JComboBox<String> ttsModel = modelCombo("eleven_v3");
    private final JTextField ttsPrefix = new JTextField("phil");
    private final JTextField ttsSpeed  = new JTextField("0.1");
    private final JTextField ttsStab   = new JTextField("0.35");
    private final JTextField ttsSim    = new JTextField("0.5");
    private final JCheckBox  ttsBoost  = new JCheckBox("speaker_boost", true);

    // ---- Timestamps (option 5) settings ----
    private final JComboBox<String> tsModel = modelCombo("eleven_v3");
    private final JTextField tsPrefix= new JTextField("b");
    private final JTextField tsStab  = new JTextField("0.9");
    private final JTextField tsSim   = new JTextField("0.5");
    private final JTextField tsStyle = new JTextField("0.0");
    private final JCheckBox  tsBoost = new JCheckBox("speaker_boost", true);

    private final java.util.List<JButton> actionButtons = new ArrayList<>();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public ElevenLabsStudio() {
        super("ElevenLabs Audio Studio  —  TTS / Scribe / Timestamps");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1080, 720);
        setLocationRelativeTo(null);
        buildUI();
    }

    // ====================================================================
    //  UI
    // ====================================================================
    private void buildUI() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);

        // ---- NORTH: shared config ----
        JPanel cfg = new JPanel(new GridBagLayout());
        cfg.setBorder(new TitledBorder("Configuration"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        cfg.add(new JLabel("API Key:"), g);
        g.gridx = 1; g.weightx = 1;
        cfg.add(apiKeyField, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        cfg.add(new JLabel("Work folder:"), g);
        g.gridx = 1; g.weightx = 1;
        cfg.add(workDirField, g);
        g.gridx = 2; g.weightx = 0;
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseDir());
        cfg.add(browse, g);

        // ---- CENTER: quotes (top) + log (bottom) ----
        linesContainer.setLayout(new BoxLayout(linesContainer, BoxLayout.Y_AXIS));
        addLineRow("", comboVal(ttsVoice));   // start with one empty row

        JPanel scriptPanel = new JPanel(new BorderLayout());
        scriptPanel.setBorder(new TitledBorder(
                "Quotes  (one per row · voice picker per row · Enter adds a new row)"));

        JPanel linesWrap = new JPanel(new BorderLayout());
        linesWrap.add(linesContainer, BorderLayout.NORTH);
        scriptPanel.add(new JScrollPane(linesWrap), BorderLayout.CENTER);

        JPanel scriptBtns = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addLine = new JButton("Add line");
        addLine.addActionListener(e -> {
            addLineRow("", comboVal(ttsVoice));
            refreshLines();
        });
        JButton loadScript = new JButton("Load file…");
        loadScript.addActionListener(e -> loadScriptFile());
        JButton saveScript = new JButton("Save as myscript.txt");
        saveScript.addActionListener(e -> saveScriptFile());
        JButton clearLog = new JButton("Clear log");
        clearLog.addActionListener(e -> logArea.setText(""));
        scriptBtns.add(addLine);
        scriptBtns.add(loadScript);
        scriptBtns.add(saveScript);
        scriptBtns.add(clearLog);
        scriptPanel.add(scriptBtns, BorderLayout.SOUTH);

        logArea.setFont(mono);
        logArea.setEditable(false);
        logArea.setBackground(new Color(0x12, 0x16, 0x1c));
        logArea.setForeground(new Color(0xd6, 0xe2, 0xea));
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Log"));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scriptPanel, logPanel);
        center.setResizeWeight(0.42);

        // ---- WEST: actions + per-action settings ----
        JPanel west = new JPanel();
        west.setLayout(new BoxLayout(west, BoxLayout.Y_AXIS));

        // 1) TTS
        JPanel ttsBox = settingsBox("1 · Generate Audio (TTS)");
        ttsVoice.setToolTipText("Default voice for new lines. Each line in the script has its own voice picker that overrides this.");
        addRow(ttsBox, "Default voice", ttsVoice);
        addRow(ttsBox, "Model ID", ttsModel);
        addRow(ttsBox, "File prefix", ttsPrefix);
        addRow(ttsBox, "speed", ttsSpeed);
        addRow(ttsBox, "stability", ttsStab);
        addRow(ttsBox, "similarity_boost", ttsSim);
        ttsBox.add(ttsBoost);
        JButton btnTts = action("Generate Audio (TTS)", this::doGenerateTts);
        ttsBox.add(Box.createVerticalStrut(4));
        ttsBox.add(btnTts);
        west.add(ttsBox);

        // 5) Timestamps
        JPanel tsBox = settingsBox("5 · TTS + Emphasized Timestamps");
        addRow(tsBox, "Model ID", tsModel);
        addRow(tsBox, "File prefix", tsPrefix);
        addRow(tsBox, "stability", tsStab);
        addRow(tsBox, "similarity_boost", tsSim);
        addRow(tsBox, "style", tsStyle);
        tsBox.add(tsBoost);
        JButton btnTs = action("TTS + Timestamps", this::doTimestamps);
        tsBox.add(Box.createVerticalStrut(4));
        tsBox.add(btnTs);
        west.add(tsBox);

        // 2/3/4
        JPanel ops = settingsBox("2 · 3 · 4 · Transcribe / Compare / Workflow");
        ops.add(action("Transcribe (Scribe v1)", this::doTranscribe));
        ops.add(Box.createVerticalStrut(4));
        ops.add(action("Compare Accuracy", this::doCompare));
        ops.add(Box.createVerticalStrut(4));
        ops.add(action("Full Workflow (1→2→3)", this::doFullWorkflow));
        west.add(ops);

        west.add(Box.createVerticalGlue());
        JScrollPane westScroll = new JScrollPane(west,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        westScroll.setBorder(null);
        westScroll.setPreferredSize(new Dimension(200, 100));

        add(cfg, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(westScroll, BorderLayout.WEST);
    }

    private JPanel settingsBox(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Width (px) of every dropdown / text field. Change this one number. */
    private static final int FIELD_W = 100;

    private void addRow(JPanel box, String label, JComponent field) {
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(80, 24));   // ← label column width
        l.setMaximumSize(new Dimension(80, 24));

        field.setPreferredSize(new Dimension(FIELD_W, 24));  // ← field width
        field.setMaximumSize(new Dimension(FIELD_W, 24));

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(l);
        row.add(Box.createHorizontalStrut(1));   // ← label↔field gap
        row.add(field);
        row.add(Box.createHorizontalGlue());     // keeps field at FIELD_W, empty space goes right
        box.add(row);
    }

    private JButton action(String text, Runnable task) {
        JButton b = new JButton(text);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        b.addActionListener(e -> runInBackground(task));
        actionButtons.add(b);
        return b;
    }

    // ====================================================================
    //  Threading helpers
    // ====================================================================
    private void runInBackground(Runnable task) {
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try { task.run(); }
                catch (Exception ex) { log("FATAL: " + ex.getMessage()); }
                return null;
            }
            @Override protected void done() { setBusy(false); }
        }.execute();
    }

    private void setBusy(boolean busy) {
        SwingUtilities.invokeLater(() -> {
            for (JButton b : actionButtons) b.setEnabled(!busy);
            setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
                    : Cursor.getDefaultCursor());
        });
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ====================================================================
    //  File / config helpers
    // ====================================================================
    private String apiKey()  { return apiKeyField.getText().trim(); }
    private File   workDir() { return new File(workDirField.getText().trim()); }

    private void chooseDir() {
        JFileChooser fc = new JFileChooser(workDir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            workDirField.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private void loadScriptFile() {
        JFileChooser fc = new JFileChooser(workDir());
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String content = new String(Files.readAllBytes(fc.getSelectedFile().toPath()), StandardCharsets.UTF_8);
                clearLineRows();
                String defaultVoice = comboVal(ttsVoice);
                for (String line : content.split("\n", -1)) {
                    String t = line.trim();
                    if (!t.isEmpty()) addLineRow(t, defaultVoice);
                }
                if (lineRows.isEmpty()) addLineRow("", defaultVoice);
                refreshLines();
                log("Loaded script: " + fc.getSelectedFile().getName());
            } catch (Exception e) { log("Error reading script: " + e.getMessage()); }
        }
    }

    private void saveScriptFile() {
        try {
            StringBuilder sb = new StringBuilder();
            for (LineRow r : lineRows) sb.append(r.text.getText()).append("\n");
            File out = new File(workDir(), "myscript.txt");
            Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            log("Saved: " + out.getAbsolutePath());
        } catch (Exception e) { log("Error saving script: " + e.getMessage()); }
    }

    /** Read quotes from the line rows: trimmed, non-empty. */
    private List<String> readQuotes() {
        List<String> quotes = new ArrayList<>();
        for (LineRow r : lineRows) {
            String t = r.text.getText().trim();
            if (!t.isEmpty()) quotes.add(t);
        }
        return quotes;
    }

    /** Read non-empty lines as (voice, text) pairs. */
    private List<QuoteItem> readQuoteItems() {
        List<QuoteItem> items = new ArrayList<>();
        for (LineRow r : lineRows) {
            String t = r.text.getText().trim();
            if (!t.isEmpty()) items.add(new QuoteItem(comboVal(r.voice), t));
        }
        return items;
    }

    // ---- line row helpers ----
    private void addLineRow(String text, String voiceId) {
        insertLineRow(lineRows.size(), text, voiceId);
    }

    private LineRow insertLineRow(int idx, String text, String voiceId) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String seed = (voiceId == null || voiceId.isEmpty()) ? comboVal(ttsVoice) : voiceId;
        JComboBox<String> voiceCb = voiceCombo(seed);
        voiceCb.setPreferredSize(new Dimension(260, 24));
        voiceCb.setMaximumSize(new Dimension(260, 24));

        JTextField textField = new JTextField(text);
        textField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JButton applyAll = new JButton("→ all");
        applyAll.setMargin(new Insets(2, 6, 2, 6));
        applyAll.setToolTipText("Apply this voice to all lines");

        JButton remove = new JButton("×");
        remove.setMargin(new Insets(2, 6, 2, 6));
        remove.setToolTipText("Remove this line");

        panel.add(voiceCb);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(textField);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(applyAll);
        panel.add(Box.createHorizontalStrut(4));
        panel.add(remove);

        LineRow lr = new LineRow(panel, voiceCb, textField);

        applyAll.addActionListener(e -> {
            String v = comboVal(lr.voice);
            for (LineRow r : lineRows) r.voice.setSelectedItem(v);
        });
        remove.addActionListener(e -> {
            lineRows.remove(lr);
            linesContainer.remove(lr.panel);
            refreshLines();
        });
        textField.addActionListener(e -> {
            int i = lineRows.indexOf(lr);
            if (i < 0) return;
            LineRow added = insertLineRow(i + 1, "", comboVal(lr.voice));
            refreshLines();
            added.text.requestFocusInWindow();
        });

        lineRows.add(idx, lr);
        linesContainer.add(panel, idx);
        return lr;
    }

    private void refreshLines() {
        linesContainer.revalidate();
        linesContainer.repaint();
    }

    private void clearLineRows() {
        lineRows.clear();
        linesContainer.removeAll();
    }

    // ====================================================================
    //  1) Text-to-Speech
    // ====================================================================
    private void doGenerateTts() {
        log("Starting Quote Audio Generation...");
        log("====================================");
        List<QuoteItem> items = readQuoteItems();
        if (items.isEmpty()) { log("No quotes found. Add quotes or load a file."); return; }

        log("Found " + items.size() + " quotes to convert.");
        String model = comboVal(ttsModel);

        String settings = "{"
                + "\"speed\":" + num(ttsSpeed) + ","
                + "\"stability\":" + num(ttsStab) + ","
                + "\"similarity_boost\":" + num(ttsSim) + ","
                + "\"use_speaker_boost\":" + ttsBoost.isSelected()
                + "}";

        int ok = 0;
        for (int i = 0; i < items.size(); i++) {
            QuoteItem it = items.get(i);
            log("Generating audio " + (i + 1) + " [voice " + it.voice + "]: \"" + preview(it.text) + "\"");
            String body = "{"
                    + "\"text\":" + jsonStr(it.text) + ","
                    + "\"model_id\":" + jsonStr(model) + ","
                    + "\"voice_settings\":" + settings
                    + "}";
            try {
                byte[] audio = postForBytes(
                        "https://api.elevenlabs.io/v1/text-to-speech/" + it.voice, body);
                File f = new File(workDir(), ttsPrefix.getText().trim() + (i + 1) + ".mp3");
                Files.write(f.toPath(), audio);
                log("Saved: " + f.getName());
                ok++;
            } catch (Exception e) {
                log("Error generating audio for quote " + (i + 1) + ": " + e.getMessage());
            }
        }
        log("");
        log("Generated " + ok + " out of " + items.size() + " audio files.");
    }

    // ====================================================================
    //  5) TTS + emphasized-word timestamps (with-timestamps endpoint)
    // ====================================================================
    private void doTimestamps() {
        log("TTS + EMPHASIZED WORD TIMESTAMPS");
        log("================================");
        List<QuoteItem> items = readQuoteItems();
        if (items.isEmpty()) { log("No quotes found."); return; }

        String model = comboVal(tsModel);
        String settings = "{"
                + "\"stability\":" + num(tsStab) + ","
                + "\"similarity_boost\":" + num(tsSim) + ","
                + "\"style\":" + num(tsStyle) + ","
                + "\"use_speaker_boost\":" + tsBoost.isSelected()
                + "}";

        List<String> allRows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            QuoteItem it = items.get(i);
            List<String> starts = generateAudioWithTimestamps(it.text, i, it.voice, model, settings);
            if (starts != null) allRows.add(String.join(",", starts));
        }
        try {
            Files.write(new File(workDir(), "all_starttimes.txt").toPath(),
                    String.join("\n", allRows).getBytes(StandardCharsets.UTF_8));
            log("");
            log("Saved combined file: all_starttimes.txt");
        } catch (Exception e) { log("Error writing all_starttimes.txt: " + e.getMessage()); }
    }

    private List<String> generateAudioWithTimestamps(String quote, int index,
                                                     String voice, String model, String settings) {
        try {
            log("[Timestamps] Generating audio " + (index + 1) + "...");
            String body = "{"
                    + "\"text\":" + jsonStr(quote) + ","
                    + "\"model_id\":" + jsonStr(model) + ","
                    + "\"voice_settings\":" + settings
                    + "}";
            String json = postForString(
                    "https://api.elevenlabs.io/v1/text-to-speech/" + voice + "/with-timestamps", body);

            Map<String, Object> root = asObj(MiniJson.parse(json));

            // 1) save audio (base64)
            String b64 = (String) root.get("audio_base64");
            File f = new File(workDir(), tsPrefix.getText().trim() + (index + 1) + ".mp3");
            Files.write(f.toPath(), Base64.getDecoder().decode(b64));
            log("Saved: " + f.getName());

            // 2) build word-level timings, ignoring [tags]
            Map<String, Object> a = asObj(root.get("alignment"));
            List<Object> chars  = asArr(a.get("characters"));
            List<Object> starts = asArr(a.get("character_start_times_seconds"));
            List<Object> ends   = asArr(a.get("character_end_times_seconds"));

            List<Word> words = new ArrayList<>();
            Word current = null;
            boolean inBracket = false;
            for (int i = 0; i < chars.size(); i++) {
                String c = String.valueOf(chars.get(i));
                if (c.equals("["))  { inBracket = true;  continue; }
                if (c.equals("]"))  { inBracket = false; continue; }
                if (inBracket) continue;

                if (c.matches("[A-Za-z']")) {
                    if (current == null)
                        current = new Word("", dbl(starts.get(i)), dbl(ends.get(i)));
                    current.word += c;
                    current.end = dbl(ends.get(i));
                } else if (current != null) {
                    words.add(current);
                    current = null;
                }
            }
            if (current != null) words.add(current);

            // 3) emphasized words = those preceded by [emphasized]
            List<String> emphasized = new ArrayList<>();
            Matcher m = Pattern.compile("\\[emphasized\\]\\s*([A-Za-z']+)",
                    Pattern.CASE_INSENSITIVE).matcher(quote);
            while (m.find()) emphasized.add(m.group(1).toLowerCase());

            log("");
            log("Emphasized word timestamps:");
            boolean[] used = new boolean[words.size()];
            List<Word> picked = new ArrayList<>();
            for (String target : emphasized) {
                int idx = -1;
                for (int k = 0; k < words.size(); k++) {
                    if (!used[k] && words.get(k).word.equalsIgnoreCase(target)) { idx = k; break; }
                }
                if (idx != -1) {
                    used[idx] = true;
                    Word w = words.get(idx);
                    picked.add(w);
                    log(String.format(Locale.US, "   \"%s\"  ->  %.3fs  (ends %.3fs)",
                            w.word, w.start, w.end));
                } else {
                    log("   \"" + target + "\"  ->  not found in alignment");
                }
            }

            // 4) save emphasized words to JSON
            StringBuilder sb = new StringBuilder("[\n");
            for (int k = 0; k < picked.size(); k++) {
                Word w = picked.get(k);
                sb.append("  {\n")
                        .append("    \"word\": ").append(jsonStr(w.word)).append(",\n")
                        .append(String.format(Locale.US, "    \"start\": %s,%n", trim(w.start)))
                        .append(String.format(Locale.US, "    \"end\": %s%n", trim(w.end)))
                        .append("  }").append(k < picked.size() - 1 ? "," : "").append("\n");
            }
            sb.append("]");
            Files.write(new File(workDir(), (index + 1) + "_timings.json").toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));

            List<String> result = new ArrayList<>();
            for (Word w : picked) result.add(String.format(Locale.US, "%.3f", w.start));
            return result;

        } catch (Exception e) {
            log("Error (timestamps) for quote " + (index + 1) + ": " + e.getMessage());
            return null;
        }
    }

    // ====================================================================
    //  2) Speech-to-Text (Scribe v1)
    // ====================================================================
    private String speechToText(File audioFile) {
        try {
            log("Transcribing audio: " + audioFile.getName());
            if (!audioFile.exists()) { log("Audio file not found: " + audioFile.getName()); return null; }

            String boundary = "----JavaBoundary" + System.currentTimeMillis();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeAscii(baos, "--" + boundary + "\r\n");
            writeAscii(baos, "Content-Disposition: form-data; name=\"model_id\"\r\n\r\n");
            writeAscii(baos, "scribe_v1\r\n");
            writeAscii(baos, "--" + boundary + "\r\n");
            writeAscii(baos, "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + audioFile.getName() + "\"\r\n");
            writeAscii(baos, "Content-Type: audio/mpeg\r\n\r\n");
            baos.write(Files.readAllBytes(audioFile.toPath()));
            writeAscii(baos, "\r\n--" + boundary + "--\r\n");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/speech-to-text"))
                    .timeout(Duration.ofMinutes(5))
                    .header("xi-api-key", apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400)
                throw new RuntimeException("HTTP " + resp.statusCode() + " " + resp.body());

            Map<String, Object> data = asObj(MiniJson.parse(resp.body()));
            Object text = data.containsKey("transcript") ? data.get("transcript") : data.get("text");
            log("Transcription complete for: " + audioFile.getName());
            return text == null ? "" : String.valueOf(text);

        } catch (Exception e) {
            log("Error transcribing audio: " + e.getMessage());
            return null;
        }
    }

    private void doTranscribe() {
        log("Starting Audio Transcription with ElevenLabs Scribe...");
        log("=====================================================");

        File[] arr = workDir().listFiles((FilenameFilter)
                (d, name) -> name.toLowerCase().endsWith(".mp3"));
        if (arr == null || arr.length == 0) { log("No MP3 files found in the folder."); return; }
        List<File> files = new ArrayList<>(Arrays.asList(arr));
        files.sort(Comparator.comparing(File::getName));

        log("Found " + files.size() + " audio files to transcribe.");
        List<String[]> transcriptions = new ArrayList<>();
        int ok = 0;
        for (int i = 0; i < files.size(); i++) {
            String t = speechToText(files.get(i));
            if (t != null) {
                transcriptions.add(new String[]{ files.get(i).getName(), t });
                ok++;
                try {
                    File tx = new File(workDir(), files.get(i).getName().replace(".mp3", "_transcription.txt"));
                    Files.write(tx.toPath(), t.getBytes(StandardCharsets.UTF_8));
                    log("Saved transcription: " + tx.getName());
                } catch (Exception e) { log("Error saving transcription: " + e.getMessage()); }
            }
            if (i < files.size() - 1) sleep(1000); // avoid rate limiting
        }

        if (!transcriptions.isEmpty()) {
            StringBuilder all = new StringBuilder();
            for (String[] t : transcriptions)
                all.append("=== ").append(t[0]).append(" ===\n").append(t[1]).append("\n\n");
            try {
                Files.write(new File(workDir(), "all_transcriptions.txt").toPath(),
                        all.toString().getBytes(StandardCharsets.UTF_8));
                log("All transcriptions saved to: all_transcriptions.txt");
            } catch (Exception e) { log("Error writing all_transcriptions.txt: " + e.getMessage()); }
        }
        log("");
        log("TRANSCRIPTION COMPLETE! Successfully transcribed " + ok + " out of " + files.size() + " files.");
    }

    // ====================================================================
    //  3) Compare original text with transcriptions
    // ====================================================================
    private void doCompare() {
        log("Comparing Original Text with ElevenLabs Transcriptions...");
        log("========================================================");

        List<String> original = readQuotes();
        if (original.isEmpty()) { log("Cannot compare - original script is empty."); return; }

        File[] arr = workDir().listFiles((FilenameFilter)
                (d, name) -> name.endsWith("_transcription.txt"));
        if (arr == null || arr.length == 0) { log("No transcription files found."); return; }
        List<File> txFiles = new ArrayList<>(Arrays.asList(arr));
        txFiles.sort(Comparator.comparing(File::getName));

        log("Accuracy Analysis:");
        log("====================");

        int totalMatches = 0;
        int n = Math.min(original.size(), txFiles.size());
        for (int i = 0; i < n; i++) {
            String transcribed;
            try {
                transcribed = new String(Files.readAllBytes(txFiles.get(i).toPath()), StandardCharsets.UTF_8).trim();
            } catch (Exception e) { log("Error reading " + txFiles.get(i).getName()); continue; }
            String orig = original.get(i);

            log("");
            log((i + 1) + ". " + txFiles.get(i).getName());
            log("Original:     \"" + orig + "\"");
            log("Transcribed:  \"" + transcribed + "\"");

            if (orig.trim().equalsIgnoreCase(transcribed.trim())) {
                totalMatches++;
                log("Status:       Perfect Match");
            } else {
                log("Status:       Differences Found");
                String[] ow = orig.toLowerCase().trim().split("\\s+");
                String[] tw = transcribed.toLowerCase().trim().split("\\s+");
                int maxLen = Math.max(ow.length, tw.length);
                int matching = 0;
                for (int j = 0; j < Math.min(ow.length, tw.length); j++)
                    if (ow[j].equals(tw[j])) matching++;
                int acc = maxLen == 0 ? 0 : Math.round(matching * 100f / maxLen);
                log("Word Accuracy: " + acc + "%");
            }
        }
        int overall = n == 0 ? 0 : Math.round(totalMatches * 100f / n);
        log("");
        log("Overall Accuracy: " + overall + "% (" + totalMatches + "/" + n + " perfect matches)");
    }

    // ====================================================================
    //  4) Full workflow
    // ====================================================================
    private void doFullWorkflow() {
        log("COMPLETE ELEVENLABS WORKFLOW");
        log("===============================");
        doGenerateTts();
        log("");
        log("Waiting 3 seconds before transcription...");
        sleep(3000);
        doTranscribe();
        doCompare();
    }

    // ====================================================================
    //  HTTP helpers
    // ====================================================================
    private byte[] postForBytes(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("xi-api-key", apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + " "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        return resp.body();
    }

    private String postForString(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("xi-api-key", apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + " " + resp.body());
        return resp.body();
    }

    // ====================================================================
    //  Small utilities
    // ====================================================================
    // ---- voice / model dropdown data (editable — you can still type a custom ID) ----
    private static final String[][] VOICES = {
            {"Liam",                 "TX3LPaxmHKxFdv7VOQHJ"},
            {"Madison Ray (anchor)", "FyrYFW3P9GUxA348YGWu"},
            {"Brian",                "XrExE9yKIg1WjnnlVkGX"},
            {"Good woman",           "pFZP5JQG7iQjIQuC4Bku"},
            {"English voice",        "nPczCjzI2devNBz1zQrb"},
            {"Bint 2",               "lzvBSKYbNWDD0a6BaJSK"},
            {"Voice TU0s",           "TU0sO9BxJtJ4GRbC43XW"},
            {"Voice cwo4",           "cwo4ramDmreHdb4b1Jxz"},
    };
    private static final String[] MODELS = {
            "eleven_v3", "eleven_multilingual_v2", "eleven_turbo_v2", "eleven_flash_v2_5"
    };

    /** Editable voice dropdown: items are IDs, popup shows "Name — ID". */
    private static JComboBox<String> voiceCombo(String defaultId) {
        JComboBox<String> cb = new JComboBox<>();
        for (String[] v : VOICES) cb.addItem(v[1]);
        cb.setEditable(true);
        cb.setSelectedItem(defaultId);
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                                                                    int index, boolean isSel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, isSel, focus);
                String id = String.valueOf(value);
                for (String[] v : VOICES)
                    if (v[1].equals(id)) { setText(v[0] + "  —  " + id); break; }
                return this;
            }
        });
        return cb;
    }

    /** Editable model dropdown. */
    private static JComboBox<String> modelCombo(String defaultId) {
        JComboBox<String> cb = new JComboBox<>(MODELS);
        cb.setEditable(true);
        cb.setSelectedItem(defaultId);
        return cb;
    }

    /** Current value of an editable combo (typed text or selected ID), trimmed. */
    private static String comboVal(JComboBox<String> cb) {
        Object o = cb.isEditable() ? cb.getEditor().getItem() : cb.getSelectedItem();
        if (o == null) o = cb.getSelectedItem();
        return o == null ? "" : o.toString().trim();
    }

    private static class Word {
        String word; double start, end;
        Word(String w, double s, double e) { word = w; start = s; end = e; }
    }

    /** One row in the quotes panel: a voice picker + a text field. */
    private static class LineRow {
        final JPanel panel;
        final JComboBox<String> voice;
        final JTextField text;
        LineRow(JPanel p, JComboBox<String> v, JTextField t) { panel = p; voice = v; text = t; }
    }

    /** A non-empty quote line paired with the voice selected on its row. */
    private static class QuoteItem {
        final String voice, text;
        QuoteItem(String v, String t) { voice = v; text = t; }
    }

    private static void writeAscii(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        b.write(d, 0, d.length);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String preview(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    /** Parse a numeric field, defaulting to 0 on bad input. */
    private static String num(JTextField f) {
        String t = f.getText().trim();
        try { Double.parseDouble(t); return t; } catch (Exception e) { return "0"; }
    }

    /** Drop a trailing .0 so 0.000 stays compact but ints look like ints. */
    private static String trim(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v))
            return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private static double dbl(Object o) { return ((Number) o).doubleValue(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    private static List<Object> asArr(Object o) { return (List<Object>) o; }

    /** JSON-escape a string value (with surrounding quotes). */
    private static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                case '\b': b.append("\\b");  break;
                case '\f': b.append("\\f");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    // ---- minimal JSON parser (objects/arrays/strings/numbers/bool/null) ----
    static final class MiniJson {
        private final String s; private int i;
        private MiniJson(String s) { this.s = s; }
        static Object parse(String s) { MiniJson p = new MiniJson(s); p.ws(); return p.value(); }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default:  return num();
            }
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); ws();
                i++; // ':'
                ws(); m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') break;
            }
            return m;
        }
        private List<Object> arr() {
            List<Object> a = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws(); a.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') break;
            }
            return a;
        }
        private String str() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  b.append('"');  break;
                        case '\\': b.append('\\'); break;
                        case '/':  b.append('/');  break;
                        case 'b':  b.append('\b'); break;
                        case 'f':  b.append('\f'); break;
                        case 'n':  b.append('\n'); break;
                        case 'r':  b.append('\r'); break;
                        case 't':  b.append('\t'); break;
                        case 'u':  b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default:   b.append(e);
                    }
                } else b.append(c);
            }
            return b.toString();
        }
        private Double num() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            return Double.parseDouble(s.substring(st, i));
        }
    }

    // ====================================================================
    //  main
    // ====================================================================
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ElevenLabsStudio().setVisible(true));
    }
}