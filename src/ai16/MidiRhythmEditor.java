package ai16;

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

    // 해상도 향상: 10틱당 1행 (더 세밀한 박자 표현)
    private final int TICKS_PER_ROW = 10; 
    private final int COLUMN_COUNT = 8; 
    private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
    
    // 판정선 설정
    private final int JUDGMENT_LINE_OFFSET = 30; // 화면 하단에서 30행 위가 판정선

    private Sequencer sequencer;
    private Sequence currentSequence;
    private Timer uiSyncTimer; 
    private float pixelsPerTick; // 정밀 스크롤 계산용

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - Ultra Precise Smooth Scroll Mode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 1000);

        initMidiEngine();
        initializeMenu();
        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 1틱당 이동할 픽셀 미리 계산
        pixelsPerTick = (float) table.getRowHeight() / TICKS_PER_ROW;

        SwingUtilities.invokeLater(() -> scrollToTick(0));
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
        JLabel lblInfo = new JLabel("  [Enter]: 재생/정지 (픽셀 단위 보정 적용) ");

        btnSave.addActionListener(e -> saveTableToTxt());
        menuBar.add(btnSave);
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(lblInfo);
        setJMenuBar(menuBar);

        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().getActionMap().put("togglePlay", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { togglePlayback(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "togglePlay");
    }

    private void togglePlayback() {
        if (sequencer.isRunning()) {
            sequencer.stop();
            if (uiSyncTimer != null) uiSyncTimer.stop();
        } else {
            long startTick = calculateTickFromView();
            sequencer.setTickPosition(startTick);
            sequencer.start();

            // 10ms(100FPS) 주기로 매우 부드럽게 스크롤 동기화
            uiSyncTimer = new Timer(10, e -> syncTableSmooth());
            uiSyncTimer.start();
        }
    }

    /**
     * 핵심 로직: 픽셀 단위 정밀 동기화
     */
    private void syncTableSmooth() {
        if (!sequencer.isRunning()) {
            if (uiSyncTimer != null) uiSyncTimer.stop();
            return;
        }

        long currentTick = sequencer.getTickPosition();
        
        // 현재 틱에 해당하는 전체 테이블 상의 Y 좌표 계산
        int rowHeight = table.getRowHeight();
        int totalRows = table.getRowCount();
        
        // 판정선의 기준 Y 좌표 (테이블 맨 아래쪽 근처)
        int judgmentLineY = (totalRows - 1 - JUDGMENT_LINE_OFFSET) * rowHeight;
        
        // 현재 소리가 나는 지점의 실시간 Y 좌표 (픽셀 단위)
        float currentNoteY = judgmentLineY - (currentTick * pixelsPerTick);
        
        JViewport viewport = scrollPane.getViewport();
        int viewHeight = viewport.getHeight();
        
        // 판정선이 화면 하단(정확히는 하단에서 OFFSET만큼 위)에 고정되도록 뷰포트 이동
        int targetViewY = (int)(currentNoteY - (viewHeight - (JUDGMENT_LINE_OFFSET * rowHeight)));
        
        viewport.setViewPosition(new Point(0, Math.max(0, targetViewY)));

        // 현재 연주되는 행 하이라이트
        int targetRow = (totalRows - 1) - JUDGMENT_LINE_OFFSET - (int)(currentTick / TICKS_PER_ROW);
        if (targetRow >= 0 && targetRow < totalRows) {
            table.setRowSelectionInterval(targetRow, targetRow);
        }
    }

    private long calculateTickFromView() {
        JViewport viewport = scrollPane.getViewport();
        // 화면 하단부의 위치를 기반으로 틱 역산
        int viewBottomY = viewport.getViewPosition().y + viewport.getHeight();
        int judgmentLineY = viewBottomY - (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        
        int totalRows = table.getRowCount();
        int baseLineY = (totalRows - 1 - JUDGMENT_LINE_OFFSET) * table.getRowHeight();
        
        float diffY = baseLineY - judgmentLineY;
        return (long) Math.max(0, diffY / pixelsPerTick);
    }

    private void scrollToTick(long tick) {
        int rowOffset = (int) (tick / TICKS_PER_ROW);
        int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - rowOffset;
        
        Rectangle rect = table.getCellRect(targetRow, 0, true);
        JViewport viewport = scrollPane.getViewport();
        int targetY = rect.y - viewport.getHeight() + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        viewport.setViewPosition(new Point(0, Math.max(0, targetY)));
    }

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { tableModel.setRowCount(3000); return; }
            
            currentSequence = MidiSystem.getSequence(file);
            sequencer.setSequence(currentSequence);

            int midiRows = (int) (currentSequence.getTickLength() / TICKS_PER_ROW);
            int totalRows = midiRows + (JUDGMENT_LINE_OFFSET * 2) + 200;
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
                                tableModel.setValueAt(new NoteData(sm.getData1()), actualRow, tc);
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
        table.setRowHeight(24); // 조금 더 촘촘하게 설정
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(35, 35, 35));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        setupColumns();

        scrollPane = new JScrollPane(table);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
        setupRowHeader();
    }

    private void setupColumns() {
        String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(i == 4 ? 100 : 70);
            cm.getColumn(i).setHeaderValue(headers[i]);
            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value instanceof NoteData data) {
                        int octave = (data.pitch / 12) - 1;
                        c.setBackground(getOctaveColor(column, octave));
                        setText("━"); // 노트를 선 형태로 시각화
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setForeground(Color.WHITE);
                    } else {
                        // 판정선 시각적 표시 (회색 선)
                        if (row == table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) {
                            c.setBackground(new Color(60, 0, 0));
                        } else {
                            c.setBackground(Color.BLACK);
                        }
                        setText("");
                    }
                    return c;
                }
            });
        }
    }

    private Color getOctaveColor(int col, int octave) {
        if (col == 0) return new Color(255, 100, 100);
        if (col == 4) return new Color(255, 255, 100);
        return new Color(100, 200, 255);
    }

    private void saveTableToTxt() {
        StringBuilder sb = new StringBuilder();
        int totalRows = tableModel.getRowCount();
        for (int r = totalRows - 1; r >= 0; r--) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                Object val = tableModel.getValueAt(r, c);
                if (val instanceof NoteData nd) {
                    // 해상도가 10으로 변했으므로 저장 시 시간 계산 주의
                    int timeT = (totalRows - 1 - JUDGMENT_LINE_OFFSET - r) * (100 / TICKS_PER_ROW);
                    sb.append("{\"seq\":").append(nd.pitch).append(", \"t\":").append(timeT).append(" },\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(sb.toString());
            JOptionPane.showMessageDialog(this, "저장 완료!");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void setupRowHeader() {
        JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
            public int getSize() { return table.getRowCount(); }
            public String getElementAt(int index) { 
                int val = table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET - index;
                return val >= 0 ? String.valueOf(val) : "-"; 
            }
        });
        rowHeader.setFixedCellHeight(table.getRowHeight());
        rowHeader.setFixedCellWidth(50);
        rowHeader.setBackground(new Color(20, 20, 20));
        rowHeader.setForeground(Color.DARK_GRAY);
        scrollPane.setRowHeaderView(rowHeader);
    }

    static class NoteData {
        int pitch;
        NoteData(int pitch) { this.pitch = pitch; }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}