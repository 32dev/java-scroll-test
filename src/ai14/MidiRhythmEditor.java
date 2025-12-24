package ai14;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.*;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;

    private final int TICKS_PER_ROW = 20; 
    private final int COLUMN_COUNT = 8; 
    private final int JUDGMENT_LINE_OFFSET = 20; 

    private Sequencer sequencer;
    private Timer uiSyncTimer; // 스크롤 동기화용 타이머
    private Sequence currentSequence;
    
    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - MIDI Engine Playback Mode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

        initMidiEngine();
        initializeMenu();
        initializeComponents();
        loadMidiAndTable("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);

        // 초기 위치 아래로 설정
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
        JButton btnPlay = new JButton("재생/일시정지 (Enter)");
        btnPlay.addActionListener(e -> togglePlayback());
        menuBar.add(btnPlay);
        setJMenuBar(menuBar);

        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "togglePlay");
        getRootPane().getActionMap().put("togglePlay", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { togglePlayback(); }
        });
    }

    private void loadMidiAndTable(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return;

            currentSequence = MidiSystem.getSequence(file);
            sequencer.setSequence(currentSequence);

            // 테이블 생성 로직 (기존과 동일하되 방향 유지)
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
                            int col = (sm.getData1() % 12) % COLUMN_COUNT;
                            tableModel.setValueAt(new NoteData(sm.getData1()), actualRow, col);
                        }
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void togglePlayback() {
        if (sequencer.isRunning()) {
            sequencer.stop();
            if (uiSyncTimer != null) uiSyncTimer.stop();
        } else {
            // 현재 화면에 보이는 행(판정선 위치)으로부터 MIDI 틱 계산하여 시작 위치 설정
            long currentTick = calculateTickFromView();
            sequencer.setTickPosition(currentTick);
            sequencer.start();
            startUiSync();
        }
    }

    private void startUiSync() {
        // 16ms(약 60fps) 주기로 MIDI 엔진의 틱을 읽어 스크롤 위치 보정
        uiSyncTimer = new Timer(16, e -> {
            if (!sequencer.isRunning()) {
                uiSyncTimer.stop();
                return;
            }
            long tick = sequencer.getTickPosition();
            updateScrollFromTick(tick);
        });
        uiSyncTimer.start();
    }

    private long calculateTickFromView() {
        JViewport viewport = scrollPane.getViewport();
        Point p = new Point(0, viewport.getViewPosition().y + viewport.getHeight() - 1);
        int currentRow = table.rowAtPoint(p);
        if (currentRow == -1) return 0;

        int rowFromBottom = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - currentRow;
        return (long) Math.max(0, rowFromBottom * TICKS_PER_ROW);
    }

    private void updateScrollFromTick(long tick) {
        int rowOffset = (int) (tick / TICKS_PER_ROW);
        int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - rowOffset;

        if (targetRow >= 0 && targetRow < table.getRowCount()) {
            table.setRowSelectionInterval(targetRow, targetRow);
            scrollToRow(targetRow);
        }
    }

    private void scrollToRow(int row) {
        Rectangle rect = table.getCellRect(row, 0, true);
        // 판정선 위치가 화면 하단에 오도록 뷰포트 위치 조정
        JViewport viewport = scrollPane.getViewport();
        int viewHeight = viewport.getHeight();
        rect.y = rect.y - viewHeight + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        rect.height = viewHeight;
        table.scrollRectToVisible(rect);
    }

    private void initializeComponents() {
        tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(25);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(40, 40, 40));
        table.setShowGrid(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // 컬럼 렌더러 설정 (기존과 동일)
        setupColumnRenderers();

        scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void setupColumnRenderers() {
        for (int i = 0; i < COLUMN_COUNT; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value instanceof NoteData) {
                        setBackground(Color.CYAN);
                        setText("●");
                    } else {
                        setBackground(Color.BLACK);
                        setText("");
                    }
                    return this;
                }
            });
        }
    }

    static class NoteData {
        int pitch;
        NoteData(int p) { this.pitch = p; }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}