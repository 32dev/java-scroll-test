package ai15;

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
    private final int JUDGMENT_LINE_OFFSET = 20; 

    private Stack<UndoItem> undoStack = new Stack<>();
    private Stack<UndoItem> redoStack = new Stack<>();

    // MIDI 전용 엔진
    private Sequencer sequencer;
    private Sequence currentSequence;
    private Timer uiSyncTimer; // UI와 사운드 동기화용 타이머

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - MIDI Engine Sync Mode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

        initMidiEngine();
        initializeMenu();
        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 초기 스크롤 위치 하단 설정
        SwingUtilities.invokeLater(() -> scrollToRow(table.getRowCount() - 1));
    }

    private void initMidiEngine() {
        try {
            sequencer = MidiSystem.getSequencer();
            sequencer.open();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void initializeMenu() {
        JMenuBar menuBar = new JMenuBar();
        JButton btnSave = new JButton("저장");
        JButton btnUndo = new JButton("Undo");
        JButton btnRedo = new JButton("Redo");
        JLabel lblInfo = new JLabel("  [Enter]: 재생/정지 (MIDI 엔진 직접 재생) ");

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
    }

    private void togglePlayback() {
        if (sequencer.isRunning()) {
            stopPlayback();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        try {
            // 1. 현재 화면 하단(판정선) 위치의 Tick 계산
            long startTick = calculateTickFromView();
            
            // 2. MIDI 엔진 설정 및 재생
            sequencer.setTickPosition(startTick);
            sequencer.start();

            // 3. UI 동기화 타이머 시작 (약 60FPS)
            uiSyncTimer = new Timer(16, e -> syncTableWithMidi());
            uiSyncTimer.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void stopPlayback() {
        if (sequencer != null) sequencer.stop();
        if (uiSyncTimer != null) uiSyncTimer.stop();
    }

    private void syncTableWithMidi() {
        if (!sequencer.isRunning()) {
            stopPlayback();
            return;
        }

        long currentTick = sequencer.getTickPosition();
        int rowOffset = (int) (currentTick / TICKS_PER_ROW);
        int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - rowOffset;

        if (targetRow >= 0 && targetRow < table.getRowCount()) {
            table.setRowSelectionInterval(targetRow, targetRow);
            scrollToRow(targetRow);
        }
    }

    private long calculateTickFromView() {
        JViewport viewport = scrollPane.getViewport();
        Point p = new Point(0, viewport.getViewPosition().y + viewport.getHeight() - 1);
        int currentRow = table.rowAtPoint(p);
        if (currentRow == -1) return 0;

        // 테이블 행 인덱스를 MIDI 틱으로 역환산
        int rowFromBottom = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - currentRow;
        return (long) Math.max(0, rowFromBottom * TICKS_PER_ROW);
    }

    private void scrollToRow(int row) {
        Rectangle rect = table.getCellRect(row, 0, true);
        JViewport viewport = scrollPane.getViewport();
        int viewHeight = viewport.getHeight();
        
        // 판정선 오프셋을 고려하여 뷰포트 위치 계산
        int targetY = rect.y - viewHeight + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        viewport.setViewPosition(new Point(0, Math.max(0, targetY)));
    }

    // --- 나머지 로드 및 데이터 관리 로직 (기존 구조 유지) ---

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { tableModel.setRowCount(2000); return; }
            
            currentSequence = MidiSystem.getSequence(file);
            sequencer.setSequence(currentSequence);

            int midiRows = (int) (currentSequence.getTickLength() / TICKS_PER_ROW);
            int totalRows = midiRows + (JUDGMENT_LINE_OFFSET * 2) + 100;
            tableModel.setRowCount(totalRows);
            
            for (Track track : currentSequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage message = event.getMessage();
                    if (message instanceof ShortMessage sm) {
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                            int rowOffset = (int) (event.getTick() / TICKS_PER_ROW);
                            int actualRow = (totalRows - 1) - JUDGMENT_LINE_OFFSET - rowOffset;
                            if (actualRow >= 0) {
                                int tc = (sm.getData1() % 12) % COLUMN_COUNT;
                                tableModel.setValueAt(new NoteData(sm.getData1(), event.getTick()), actualRow, tc);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

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

        scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
        setupRowHeader();
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
                    if (value instanceof NoteData data) {
                        int octave = (data.pitch / 12) - 1;
                        c.setBackground(getOctaveColor(column, octave));
                        setText(NOTE_NAMES[data.pitch % 12] + octave);
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setForeground(octave > 5 ? Color.BLACK : Color.WHITE);
                    } else {
                        c.setBackground(Color.BLACK);
                        setText("");
                    }
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

    private void saveTableToTxt() {
        StringBuilder sb = new StringBuilder();
        int totalRows = tableModel.getRowCount();
        for (int r = totalRows - 1; r >= 0; r--) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                Object val = tableModel.getValueAt(r, c);
                if (val instanceof NoteData nd) {
                    int timeT = (totalRows - 1 - JUDGMENT_LINE_OFFSET - r) * 10;
                    sb.append("{\"seq\":").append(nd.pitch).append(", \"t\":").append(timeT).append(" },\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(sb.toString());
            JOptionPane.showMessageDialog(this, "저장 성공!");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void setupRowHeader() {
        JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
            public int getSize() { return table.getRowCount(); }
            public String getElementAt(int index) { 
                int displayVal = table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET - index;
                return displayVal >= 0 ? String.valueOf(displayVal) : "-"; 
            }
        });
        rowHeader.setFixedCellHeight(table.getRowHeight());
        rowHeader.setFixedCellWidth(50);
        rowHeader.setBackground(new Color(30, 30, 30));
        rowHeader.setForeground(Color.LIGHT_GRAY);
        scrollPane.setRowHeaderView(rowHeader);
    }

    // Undo/Redo 생략 (필요시 기존 로직 유지 가능)
    private void undo() {}
    private void redo() {}

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