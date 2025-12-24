package ai19;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Objects;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.*;

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
    private float speedMultiplier = 1.0f; 

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - Drag & Drop / Note Swap Mode");
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

    private void initializeComponents() {
        tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int columnIndex) { return NoteData.class; }
        };
        
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(40, 40, 40));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        // --- 드래그 앤 드롭 설정 ---
        table.setDragEnabled(true);
        table.setDropMode(DropMode.USE_SELECTION);
        table.setTransferHandler(new NoteTransferHandler());
        
        setupColumns();

        scrollPane = new JScrollPane(table);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
        setupRowHeader();
    }

    private void setupColumns() {
        String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(i == 4 ? 100 : 80);
            cm.getColumn(i).setHeaderValue(headers[i]);
            cm.getColumn(i).setCellRenderer(new NoteCellRenderer());
        }
    }

    // --- 노트 렌더러 (텍스트 및 색상) ---
    private class NoteCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof NoteData data) {
                int octave = (data.pitch / 12) - 1;
                c.setBackground(getOctaveColor(column, octave));
                c.setForeground(Color.BLACK); // 텍스트를 검은색으로 해서 잘 보이게 함
                setText(String.valueOf(data.pitch)); // 피치 번호 표시
                setFont(new Font("Arial", Font.BOLD, 10));
                setHorizontalAlignment(SwingConstants.CENTER);
            } else {
                c.setBackground(row == table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET ? new Color(60, 0, 0) : Color.BLACK);
                setText("");
            }
            if (isSelected) c.setBackground(c.getBackground().brighter());
            return c;
        }
    }

    // --- 드래그 앤 드롭 핸들러 (위치 교환 로직 포함) ---
    private class NoteTransferHandler extends TransferHandler {
        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override
        protected Transferable createTransferable(JComponent c) {
            JTable t = (JTable) c;
            int row = t.getSelectedRow();
            int col = t.getSelectedColumn();
            NoteData data = (NoteData) t.getValueAt(row, col);
            return data != null ? new NoteTransferable(data, row, col) : null;
        }

        @Override
        public boolean canImport(TransferSupport support) { return support.isDataFlavorSupported(NoteTransferable.FLAVOR); }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            JTable.DropLocation dl = (JTable.DropLocation) support.getDropLocation();
            int targetRow = dl.getRow();
            int targetCol = dl.getColumn();

            try {
                NoteTransferable t = (NoteTransferable) support.getTransferable().getTransferData(NoteTransferable.FLAVOR);
                NoteData sourceData = t.data;
                NoteData targetData = (NoteData) table.getValueAt(targetRow, targetCol);

                // 교환(Swap) 로직
                tableModel.setValueAt(targetData, t.sourceRow, t.sourceCol);
                tableModel.setValueAt(sourceData, targetRow, targetCol);
                
                return true;
            } catch (Exception e) { return false; }
        }
    }

    // DnD용 데이터 포맷
    private static class NoteTransferable implements Transferable {
        public static final DataFlavor FLAVOR = new DataFlavor(NoteData.class, "NoteData");
        NoteData data;
        int sourceRow, sourceCol;
        NoteTransferable(NoteData data, int r, int c) { this.data = data; this.sourceRow = r; this.sourceCol = c; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{FLAVOR}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return FLAVOR.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) { return this; }
    }

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { tableModel.setRowCount(3000); return; }
            currentSequence = MidiSystem.getSequence(file);
            sequencer.setSequence(currentSequence);
            
            int totalRows = (int) (currentSequence.getTickLength() / TICKS_PER_ROW) + 500;
            tableModel.setRowCount(totalRows);

            for (Track track : currentSequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage message = event.getMessage();
                    if (message instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        int rowOffset = (int) (event.getTick() / TICKS_PER_ROW);
                        int actualRow = (totalRows - 1) - JUDGMENT_LINE_OFFSET - rowOffset;
                        int preferredCol = (sm.getData1() % 12) % COLUMN_COUNT;

                        // --- 겹침 처리: 비어있는 옆칸 찾기 ---
                        int finalCol = findEmptyColumn(actualRow, preferredCol);
                        if (actualRow >= 0 && finalCol != -1) {
                            tableModel.setValueAt(new NoteData(sm.getData1()), actualRow, finalCol);
                        }
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private int findEmptyColumn(int row, int startCol) {
        if (tableModel.getValueAt(row, startCol) == null) return startCol;
        // 옆으로 한 칸씩 탐색
        for (int i = 1; i < COLUMN_COUNT; i++) {
            int right = startCol + i;
            int left = startCol - i;
            if (right < COLUMN_COUNT && tableModel.getValueAt(row, right) == null) return right;
            if (left >= 0 && tableModel.getValueAt(row, left) == null) return left;
        }
        return -1; // 빈 공간 없음
    }

    // --- 이후 메뉴 및 헬퍼 메서드들 ---
    private void adjustSpeed(float delta) {
        long currentTick = calculateTickFromView();
        speedMultiplier = Math.max(0.2f, Math.min(5.0f, speedMultiplier + delta));
        int newRowHeight = (int)(24 * speedMultiplier);
        table.setRowHeight(newRowHeight);
        ((JList<?>)scrollPane.getRowHeader().getView()).setFixedCellHeight(newRowHeight);
        scrollToTick(currentTick);
    }

    private void togglePlayback() {
        if (sequencer.isRunning()) {
            sequencer.stop();
            if (uiSyncTimer != null) uiSyncTimer.stop();
        } else {
            sequencer.setTickPosition(calculateTickFromView());
            sequencer.start();
            uiSyncTimer = new Timer(10, e -> syncTableSmooth());
            uiSyncTimer.start();
        }
    }

    private void syncTableSmooth() {
        if (!sequencer.isRunning()) { uiSyncTimer.stop(); return; }
        long currentTick = sequencer.getTickPosition();
        int rowHeight = table.getRowHeight();
        float currentNoteY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * rowHeight) - (currentTick * ((float)rowHeight / TICKS_PER_ROW));
        int targetViewY = (int)(currentNoteY - (scrollPane.getViewport().getHeight() - (JUDGMENT_LINE_OFFSET * rowHeight)));
        scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetViewY)));
    }

    private long calculateTickFromView() {
        int viewBottomY = scrollPane.getViewport().getViewPosition().y + scrollPane.getViewport().getHeight();
        int judgmentLineY = viewBottomY - (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        float diffY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * table.getRowHeight()) - judgmentLineY;
        return (long) Math.max(0, diffY / ((float)table.getRowHeight() / TICKS_PER_ROW));
    }

    private void scrollToTick(long tick) {
        int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - (int) (tick / TICKS_PER_ROW);
        Rectangle rect = table.getCellRect(targetRow, 0, true);
        int targetY = rect.y - scrollPane.getViewport().getHeight() + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetY)));
    }

    private void initializeMenu() {
        JMenuBar menuBar = new JMenuBar();
        JButton btnSave = new JButton("저장");
        JLabel lblInfo = new JLabel("  [Enter]:재생 | [1,2]:속도 | 드래그하여 노트 이동/교환");
        btnSave.addActionListener(e -> saveTableToTxt());
        menuBar.add(btnSave); menuBar.add(Box.createHorizontalGlue()); menuBar.add(lblInfo);
        setJMenuBar(menuBar);

        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "play");
        am.put("play", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { togglePlayback(); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "sDown");
        am.put("sDown", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { adjustSpeed(-0.2f); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "sUp");
        am.put("sUp", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { adjustSpeed(0.2f); } });
    }

    private Color getOctaveColor(int col, int octave) {
        if (col == 0) return new Color(255, 150, 150);
        if (col == 4) return new Color(255, 255, 150);
        return new Color(150, 200, 255);
    }

    private void saveTableToTxt() {
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            for (int r = tableModel.getRowCount() - 1; r >= 0; r--) {
                for (int c = 0; c < COLUMN_COUNT; c++) {
                    if (tableModel.getValueAt(r, c) instanceof NoteData nd) {
                        int timeT = (tableModel.getRowCount() - 1 - JUDGMENT_LINE_OFFSET - r) * 10;
                        out.println("{\"pitch\":" + nd.pitch + ", \"t\":" + timeT + "},");
                    }
                }
            }
            JOptionPane.showMessageDialog(this, "저장 성공!");
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
        rowHeader.setForeground(Color.GRAY);
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