package ai13;

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
    
    // 판정선 설정 (소리가 나는 위치 오프셋)
    private final int JUDGMENT_LINE_OFFSET = 20; // 재생 바보다 20행 아래에 있는 노트의 소리를 출력

    private Stack<UndoItem> undoStack = new Stack<>();
    private Stack<UndoItem> redoStack = new Stack<>();

    private Timer playbackTimer;
    private boolean isPlaying = false;
    private long playbackStartTime;
    private int startRowIndex;
    
    private Synthesizer synthesizer;
    private MidiChannel midiChannel;
    private double currentBPM = 120.0;
    private double msPerRow;

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - Bottom Judgment Sync Mode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

        initMidiSynth();
        initializeMenu();
        initializeComponents();
        loadMidiFile("input.mid");

        msPerRow = (60000.0 / currentBPM) * (TICKS_PER_ROW / 480.0);
        setupPlaybackLogic();

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 시작 시 맨 아래(패딩 포함)로 스크롤
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
        JLabel lblInfo = new JLabel("  [Enter]: 재생 (판정선 오프셋: " + JUDGMENT_LINE_OFFSET + "행) ");

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
        // 화면 하단 끝 행 찾기
        Point p = new Point(0, viewport.getViewPosition().y + viewport.getHeight() - 1);
        startRowIndex = table.rowAtPoint(p);
        if (startRowIndex == -1) startRowIndex = table.getRowCount() - 1;

        playbackStartTime = System.nanoTime();
        isPlaying = true;
        lastProcessedRow = -1;
        playbackTimer.start();
    }

    private void stopPlayback() {
        isPlaying = false;
        if (playbackTimer != null) playbackTimer.stop();
        midiChannel.allNotesOff();
    }

    private void setupPlaybackLogic() {
        playbackTimer = new Timer(5, e -> {
            long elapsedNano = System.nanoTime() - playbackStartTime;
            double elapsedMs = elapsedNano / 1_000_000.0;
            
            int rowsMoved = (int) (elapsedMs / msPerRow);
            int newRowIndex = startRowIndex - rowsMoved;

            if (newRowIndex < 0) {
                stopPlayback();
                return;
            }

            if (newRowIndex != lastProcessedRow) {
                // UI는 현재 스크롤 위치를 유지하고
                table.setRowSelectionInterval(newRowIndex, newRowIndex);
                Rectangle rect = table.getCellRect(newRowIndex, 0, true);
                table.scrollRectToVisible(rect);

                // 실제 소리는 판정선 오프셋(아래쪽)에 위치한 행의 것을 재생
                int soundRow = newRowIndex; 
                playRowSound(soundRow);
                
                lastProcessedRow = newRowIndex;
            }
        });
    }

    private int lastProcessedRow = -1;

    private void playRowSound(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= table.getRowCount()) return;
        for (int c = 0; c < COLUMN_COUNT; c++) {
            Object val = table.getValueAt(rowIndex, c);
            if (val instanceof NoteData) {
                midiChannel.noteOn(((NoteData) val).pitch, 100);
            }
        }
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
        int totalRows = tableModel.getRowCount();
        // 실제 데이터 행(패딩 제외)만 저장하려면 범위를 조절할 수 있습니다.
        // 여기서는 바닥(패딩 끝)을 0으로 잡고 저장합니다.
        for (int r = totalRows - 1; r >= 0; r--) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                Object val = tableModel.getValueAt(r, c);
                if (val instanceof NoteData) {
                    NoteData nd = (NoteData) val;
                    int timeT = (totalRows - 1 - r) * 10;
                    sb.append("{\"seq\":").append(nd.pitch).append(", \"t\":").append(timeT).append(" },\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(sb.toString());
            JOptionPane.showMessageDialog(this, "저장 성공!");
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
                    } else { 
                        // 판정선 시각화 (선택사항: 특정 행에 색상 부여 가능)
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

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { tableModel.setRowCount(2000); return; }
            Sequence sequence = MidiSystem.getSequence(file);
            
            // 앞뒤로 여유 공간(Padding) 추가
            int midiRows = (int) (sequence.getTickLength() / TICKS_PER_ROW);
            int totalRows = midiRows + (JUDGMENT_LINE_OFFSET * 2) + 100;
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
                            // 판정선 오프셋만큼 띄워서 배치
                            int actualRow = (totalRows - 1) - JUDGMENT_LINE_OFFSET - rowOffset;
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