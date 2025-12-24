package ai18;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;

    private final int TICKS_PER_ROW = 10; 
    private final int COLUMN_COUNT = 8; 
    private final int JUDGMENT_LINE_OFFSET = 30;

    private Sequencer sequencer;
    private Sequence currentSequence;
    private Timer uiSyncTimer; 
    
    // 속도 조절을 위한 변수
    private float speedMultiplier = 1.0f; 
    private final float BASE_PIXELS_PER_TICK = 2.4f; // 기본 rowHeight(24)/TICKS_PER_ROW(10)

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - Speed Control (Press 1, 2)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 1000);

        initMidiEngine();
        initializeMenu();
        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
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
        JLabel lblInfo = new JLabel("  [Enter]: 재생/정지 | [1]: 느리게 | [2]: 빠르게 (현재: " + speedMultiplier + "x)");

        btnSave.addActionListener(e -> saveTableToTxt());
        menuBar.add(btnSave);
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(lblInfo);
        setJMenuBar(menuBar);

        // 단축키 설정
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "togglePlay");
        am.put("togglePlay", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { togglePlayback(); }
        });

        // 1번 키: 속도 감소 (느리게)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "speedDown");
        am.put("speedDown", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                adjustSpeed(-0.2f);
                lblInfo.setText("  [Enter]: 재생/정지 | [1]: 느리게 | [2]: 빠르게 (현재: " + String.format("%.1f", speedMultiplier) + "x)");
            }
        });

        // 2번 키: 속도 증가 (빠르게)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "speedUp");
        am.put("speedUp", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                adjustSpeed(0.2f);
                lblInfo.setText("  [Enter]: 재생/정지 | [1]: 느리게 | [2]: 빠르게 (현재: " + String.format("%.1f", speedMultiplier) + "x)");
            }
        });
    }

    private void adjustSpeed(float delta) {
        long currentTick = calculateTickFromView();
        speedMultiplier = Math.max(0.2f, Math.min(5.0f, speedMultiplier + delta));
        
        // 속도가 변하면 테이블의 행 높이를 조절하여 시각적 간격을 벌립니다.
        int newRowHeight = (int)(24 * speedMultiplier);
        table.setRowHeight(newRowHeight);
        ((JList<?>)scrollPane.getRowHeader().getView()).setFixedCellHeight(newRowHeight);
        
        // 변경된 속도 기준으로 현재 보던 위치(Tick)로 다시 스크롤
        scrollToTick(currentTick);
    }

    private float getPixelsPerTick() {
        return (float) table.getRowHeight() / TICKS_PER_ROW;
    }

    private void togglePlayback() {
        if (sequencer.isRunning()) {
            sequencer.stop();
            if (uiSyncTimer != null) uiSyncTimer.stop();
        } else {
            long startTick = calculateTickFromView();
            sequencer.setTickPosition(startTick);
            sequencer.start();
            uiSyncTimer = new Timer(10, e -> syncTableSmooth());
            uiSyncTimer.start();
        }
    }

    private void syncTableSmooth() {
        if (!sequencer.isRunning()) {
            if (uiSyncTimer != null) uiSyncTimer.stop();
            return;
        }

        long currentTick = sequencer.getTickPosition();
        int rowHeight = table.getRowHeight();
        int totalRows = table.getRowCount();
        
        int judgmentLineY = (totalRows - 1 - JUDGMENT_LINE_OFFSET) * rowHeight;
        float currentNoteY = judgmentLineY - (currentTick * getPixelsPerTick());
        
        JViewport viewport = scrollPane.getViewport();
        int viewHeight = viewport.getHeight();
        int targetViewY = (int)(currentNoteY - (viewHeight - (JUDGMENT_LINE_OFFSET * rowHeight)));
        
        viewport.setViewPosition(new Point(0, Math.max(0, targetViewY)));

        int targetRow = (totalRows - 1) - JUDGMENT_LINE_OFFSET - (int)(currentTick / TICKS_PER_ROW);
        if (targetRow >= 0 && targetRow < totalRows) {
            table.setRowSelectionInterval(targetRow, targetRow);
        }
    }

    private long calculateTickFromView() {
        JViewport viewport = scrollPane.getViewport();
        int viewBottomY = viewport.getViewPosition().y + viewport.getHeight();
        int judgmentLineY = viewBottomY - (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        int totalRows = table.getRowCount();
        int baseLineY = (totalRows - 1 - JUDGMENT_LINE_OFFSET) * table.getRowHeight();
        float diffY = baseLineY - judgmentLineY;
        return (long) Math.max(0, diffY / getPixelsPerTick());
    }

    private void scrollToTick(long tick) {
        int rowOffset = (int) (tick / TICKS_PER_ROW);
        int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - rowOffset;
        
        Rectangle rect = table.getCellRect(targetRow, 0, true);
        JViewport viewport = scrollPane.getViewport();
        int targetY = rect.y - viewport.getHeight() + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        viewport.setViewPosition(new Point(0, Math.max(0, targetY)));
    }

    // ... (loadMidiFile, initializeComponents, setupColumns, getOctaveColor, saveTableToTxt, setupRowHeader, NoteData 클래스는 기존과 동일)
    // 단, initializeComponents 내에서 table.setRowHeight(24) 부분은 유지하되 
    // adjustSpeed에서 관리되도록 구성됨.
    
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
        table.setRowHeight(24);
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
                        c.setBackground(getOctaveColor(column, (data.pitch / 12) - 1));
                        setText("━");
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setForeground(Color.WHITE);
                    } else {
                        c.setBackground(row == table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET ? new Color(60, 0, 0) : Color.BLACK);
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