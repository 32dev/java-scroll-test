package ai12;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Stack;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.*;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;

    private final int TICKS_PER_ROW = 20; 
    private final int COLUMN_COUNT = 8; 
    private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

    private Stack<UndoItem> undoStack = new Stack<>();
    private Stack<UndoItem> redoStack = new Stack<>();

    private Timer playbackTimer;
    private boolean isPlaying = false;
    private long playbackStartTime; // 재생 시작 나노초 시간
    private int startRowIndex;      // 재생 시작 시점의 행 위치
    
    private Synthesizer synthesizer;
    private MidiChannel midiChannel;
    private double currentBPM = 120.0;
    private double msPerRow; // 한 행당 소요되는 밀리초

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - High Precision Sync Mode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

        initMidiSynth();
        initializeMenu();
        initializeComponents();
        loadMidiFile("input.mid");

        // BPM에 따른 한 행당 정확한 밀리초 계산
        // 480 Resolution 기준, TICKS_PER_ROW(20)는 1/24 박자
        msPerRow = (60000.0 / currentBPM) * (TICKS_PER_ROW / 480.0);

        setupPlaybackLogic();

        setLocationRelativeTo(null);
        setVisible(true);
        
        SwingUtilities.invokeLater(() -> {
            int lastRow = table.getRowCount() - 1;
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
        });
    }

    private void initMidiSynth() {
        try {
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
            midiChannel = synthesizer.getChannels()[0];
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void initializeMenu() {
        JMenuBar menuBar = new JMenuBar();
        JButton btnSave = new JButton("저장");
        JButton btnUndo = new JButton("Undo");
        JButton btnRedo = new JButton("Redo");
        JLabel lblInfo = new JLabel("  [Enter]: 재생 (나노초 싱크 보정 적용) ");

        btnSave.addActionListener(e -> saveTableToTxt());
        btnUndo.addActionListener(e -> undo());
        btnRedo.addActionListener(e -> redo());

        menuBar.add(btnSave); menuBar.add(btnUndo); menuBar.add(btnRedo);
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(lblInfo);
        setJMenuBar(menuBar);

        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "togglePlay");
        am.put("togglePlay", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { togglePlayback(); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        am.put("undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { undo(); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        am.put("redo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { redo(); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        am.put("save", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { saveTableToTxt(); } });
    }

    private void togglePlayback() {
        if (isPlaying) stopPlayback();
        else startPlayback();
    }

    private void startPlayback() {
        JViewport viewport = scrollPane.getViewport();
        Point p = new Point(0, viewport.getViewPosition().y + viewport.getHeight() - 1);
        startRowIndex = table.rowAtPoint(p);
        if (startRowIndex == -1) startRowIndex = table.getRowCount() - 1;

        playbackStartTime = System.nanoTime(); // 시작 시간 정밀 기록
        isPlaying = true;
        playbackTimer.start();
    }

    private void stopPlayback() {
        isPlaying = false;
        if (playbackTimer != null) playbackTimer.stop();
        midiChannel.allNotesOff();
    }

    private void setupPlaybackLogic() {
        // 타이머는 빠르게(5ms 간격) 돌면서 실제 경과 시간에 맞춰 행을 계산함
        playbackTimer = new Timer(5, e -> {
            long elapsedNano = System.nanoTime() - playbackStartTime;
            double elapsedMs = elapsedNano / 1_000_000.0;
            
            // 실제 흘러야 하는 행의 개수 계산
            int rowsToMove = (int) (elapsedMs / msPerRow);
            int newRowIndex = startRowIndex - rowsToMove;

            if (newRowIndex < 0) {
                stopPlayback();
                return;
            }

            // 행이 실제로 바뀌었을 때만 사운드 및 UI 갱신
            if (newRowIndex != lastProcessedRow) {
                processRow(newRowIndex);
                lastProcessedRow = newRowIndex;
            }
        });
    }

    private int lastProcessedRow = -1;

    private void processRow(int rowIndex) {
        table.setRowSelectionInterval(rowIndex, rowIndex);
        Rectangle rect = table.getCellRect(rowIndex, 0, true);
        table.scrollRectToVisible(rect);

        for (int c = 0; c < COLUMN_COUNT; c++) {
            Object val = table.getValueAt(rowIndex, c);
            if (val instanceof NoteData) {
                midiChannel.noteOn(((NoteData) val).pitch, 100);
            }
        }
    }

    // --- 이하 로드, 저장, UI 설정 (기존과 동일) ---
    private void initializeComponents() {
        tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(45, 45, 45));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        setupColumns();
        setupDragAndDrop();

        scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
        setupRowHeader();
    }

    private void setupDragAndDrop() {
        MouseAdapter ma = new MouseAdapter() {
            private int startCol = -1, startRow = -1;
            private NoteData dragData = null;
            @Override public void mousePressed(MouseEvent e) {
                if (isPlaying) return;
                startRow = table.rowAtPoint(e.getPoint());
                startCol = table.columnAtPoint(e.getPoint());
                if (startRow != -1 && startCol != -1) {
                    Object val = table.getValueAt(startRow, startCol);
                    if (val instanceof NoteData) {
                        dragData = (NoteData) val;
                        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    }
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragData == null) return;
                int endCol = table.columnAtPoint(e.getPoint());
                if (endCol != -1 && endCol != startCol) {
                    Object targetVal = table.getValueAt(startRow, endCol);
                    pushUndo(startRow, startCol, endCol, dragData, (NoteData)targetVal);
                    table.setValueAt(targetVal, startRow, startCol);
                    table.setValueAt(dragData, startRow, endCol);
                }
                dragData = null;
                table.setCursor(Cursor.getDefaultCursor());
            }
        };
        table.addMouseListener(ma);
        table.addMouseMotionListener(ma);
    }

    private void pushUndo(int row, int fromCol, int toCol, NoteData fromData, NoteData toData) {
        undoStack.push(new UndoItem(row, fromCol, toCol, fromData, toData));
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        UndoItem item = undoStack.pop();
        redoStack.push(item);
        table.setValueAt(item.fromData, item.row, item.fromCol);
        table.setValueAt(item.toData, item.row, item.toCol);
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        UndoItem item = redoStack.pop();
        undoStack.push(item);
        table.setValueAt(item.toData, item.row, item.fromCol);
        table.setValueAt(item.fromData, item.row, item.toCol);
    }

    private void saveTableToTxt() {
        StringBuilder sb = new StringBuilder();
        int maxR = tableModel.getRowCount();
        for (int r = maxR - 1; r >= 0; r--) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                Object val = tableModel.getValueAt(r, c);
                if (val instanceof NoteData) {
                    NoteData nd = (NoteData) val;
                    int timeT = (maxR - 1 - r) * 10;
                    sb.append("{\"seq\":").append(nd.pitch).append(", \"t\":").append(timeT).append(" },\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(sb.toString());
            JOptionPane.showMessageDialog(this, "저장 완료!");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void setupColumns() {
        String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(i == 4 ? 90 : 65);
            cm.getColumn(i).setHeaderValue(headers[i]);
            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value instanceof NoteData) {
                        NoteData data = (NoteData) value;
                        int octave = (data.pitch / 12) - 1;
                        c.setBackground(getOctaveColor(column, octave));
                        setText(NOTE_NAMES[data.pitch % 12] + octave);
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setForeground(octave > 5 ? Color.BLACK : Color.WHITE);
                    } else { c.setBackground(Color.BLACK); setText(""); }
                    ((JComponent) c).setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.DARK_GRAY));
                    return c;
                }
            });
        }
    }

    private Color getOctaveColor(int col, int octave) {
        Color base;
        switch (col) { case 0: base = Color.PINK; break; case 4: base = Color.YELLOW; break; default: base = Color.CYAN; break; }
        float b = 0.4f + (Math.max(0, octave) * 0.08f);
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1], Math.min(1.0f, b));
    }

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { tableModel.setRowCount(3000); return; }
            Sequence sequence = MidiSystem.getSequence(file);
            int totalRows = (int) (sequence.getTickLength() / TICKS_PER_ROW) + 500;
            tableModel.setRowCount(totalRows);
            
            for (Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage message = event.getMessage();
                    if (message instanceof ShortMessage) {
                        ShortMessage sm = (ShortMessage) message;
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                            int pitch = sm.getData1();
                            int rowOffset = (int) (event.getTick() / TICKS_PER_ROW);
                            int actualRow = totalRows - 1 - rowOffset;
                            if (actualRow >= 0) {
                                int tc = (pitch % 12) % COLUMN_COUNT;
                                int fc = findEmptyColumn(actualRow, tc);
                                if (fc != -1) tableModel.setValueAt(new NoteData(pitch, event.getTick()), actualRow, fc);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private int findEmptyColumn(int row, int targetCol) {
        if (tableModel.getValueAt(row, targetCol) == null) return targetCol;
        for (int i = 1; i < COLUMN_COUNT; i++) {
            int r = targetCol + i; if (r < COLUMN_COUNT && tableModel.getValueAt(row, r) == null) return r;
            int l = targetCol - i; if (l >= 0 && tableModel.getValueAt(row, l) == null) return l;
        }
        return -1;
    }

    private void setupRowHeader() {
        JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
            public int getSize() { return table.getRowCount(); }
            public String getElementAt(int index) { return String.valueOf(table.getRowCount() - 1 - index); }
        });
        rowHeader.setFixedCellHeight(table.getRowHeight());
        rowHeader.setFixedCellWidth(50);
        rowHeader.setBackground(new Color(30, 30, 30));
        rowHeader.setForeground(Color.LIGHT_GRAY);
        scrollPane.setRowHeaderView(rowHeader);
    }

    static class NoteData {
        int pitch; long tick;
        NoteData(int pitch, long tick) { this.pitch = pitch; this.tick = tick; }
    }
    static class UndoItem {
        int row, fromCol, toCol; NoteData fromData, toData;
        UndoItem(int r, int fc, int tc, NoteData fd, NoteData td) { this.row = r; this.fromCol = fc; this.toCol = tc; this.fromData = fd; this.toData = td; }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}