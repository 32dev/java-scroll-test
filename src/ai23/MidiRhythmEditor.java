package ai23;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Stack;

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
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;
    private JLabel lblStatus; 

    private final int TICKS_PER_ROW = 10;
    private final int COLUMN_COUNT = 8;
    private final int JUDGMENT_LINE_OFFSET = 30;
    private final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private Sequencer sequencer;
    private Timer uiSyncTimer;
    private float speedMultiplier = 1.0f; 

    private Stack<Object[][]> undoStack = new Stack<>();
    private Stack<Object[][]> redoStack = new Stack<>();
    private NoteData clipboardNode = null;

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor Pro - [F5] Play/Stop Control");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 1200);

        initMidiEngine();
        initializeComponents();
        initializeMenu();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 초기 포커스 설정
        this.requestFocusInWindow();
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
        };

        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(45, 45, 45));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setCellSelectionEnabled(true);
        
        // 드래그 앤 드롭 설정
        table.setDragEnabled(true);
        table.setDropMode(DropMode.USE_SELECTION);
        table.setTransferHandler(new NoteTransferHandler());

        setupColumns();

        scrollPane = new JScrollPane(table);
        scrollPane.getVerticalScrollBar().setUnitIncrement(30);
        
        lblStatus = new JLabel(" [F5]: 재생/정지 | [1,2]: 배속 | [Ctrl+S]: 저장");
        lblStatus.setOpaque(true);
        lblStatus.setBackground(new Color(25, 25, 25));
        lblStatus.setForeground(Color.GREEN);
        lblStatus.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        lblStatus.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        add(scrollPane, BorderLayout.CENTER);
        add(lblStatus, BorderLayout.SOUTH);
        setupRowHeader();
    }

    private void initializeMenu() {
        JMenuBar menuBar = new JMenuBar();
        JButton btnSave = new JButton("저장 (Ctrl+S)");
        btnSave.setFocusable(false);
        btnSave.addActionListener(e -> saveTableToTxt());
        menuBar.add(btnSave);
        setJMenuBar(menuBar);

        // --- 전역 단축키 매핑 (F5로 변경) ---
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        // F5: 재생 및 정지
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "togglePlay");
        am.put("togglePlay", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { togglePlayback(); }
        });

        // Ctrl+S: 저장
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        am.put("save", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { saveTableToTxt(); } });

        // 1, 2: 배속 조절
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "speedDown");
        am.put("speedDown", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { adjustSpeed(-0.2f); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "speedUp");
        am.put("speedUp", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { adjustSpeed(0.2f); } });

        // Ctrl+Z, Ctrl+Y: Undo/Redo
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        am.put("undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { undo(); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        am.put("redo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { redo(); } });
        
        // Delete: 삭제
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        am.put("delete", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { deleteSelected(); } });
    }

    private void togglePlayback() {
        if (sequencer == null) return;

        if (sequencer.isRunning()) {
            sequencer.stop();
            if (uiSyncTimer != null) uiSyncTimer.stop();
            lblStatus.setText(" ■ 정지됨 (배속: " + String.format("%.1f", speedMultiplier) + "x)");
        } else {
            long currentTick = calculateTickFromView();
            sequencer.setTickPosition(currentTick);
            sequencer.start();
            
            if (uiSyncTimer != null) uiSyncTimer.stop();
            uiSyncTimer = new Timer(15, e -> syncTableSmooth());
            uiSyncTimer.start();
            
            lblStatus.setText(" ▶ 재생 중... (배속: " + String.format("%.1f", speedMultiplier) + "x)");
        }
    }

    // --- 이하 로직 (배속, 저장, 스크롤 등) ---

    private void adjustSpeed(float delta) {
        long currentTick = calculateTickFromView();
        speedMultiplier = Math.max(0.2f, Math.min(5.0f, speedMultiplier + delta));
        int newRowHeight = (int)(24 * speedMultiplier);
        table.setRowHeight(newRowHeight);
        ((JList<?>)scrollPane.getRowHeader().getView()).setFixedCellHeight(newRowHeight);
        scrollToTick(currentTick);
        lblStatus.setText(" 배속 변경: " + String.format("%.1f", speedMultiplier) + "x");
    }

    private void syncTableSmooth() {
        if (!sequencer.isRunning()) return;
        long currentTick = sequencer.getTickPosition();
        int rowHeight = table.getRowHeight();
        float pixelsPerTick = (float)rowHeight / TICKS_PER_ROW;
        float currentNoteY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * rowHeight) - (currentTick * pixelsPerTick);
        int targetViewY = (int)(currentNoteY - (scrollPane.getViewport().getHeight() - (JUDGMENT_LINE_OFFSET * rowHeight)));
        scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetViewY)));
    }

    private long calculateTickFromView() {
        int viewBottomY = scrollPane.getViewport().getViewPosition().y + scrollPane.getViewport().getHeight();
        int judgmentLineY = viewBottomY - (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        float pixelsPerTick = (float)table.getRowHeight() / TICKS_PER_ROW;
        float diffY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * table.getRowHeight()) - judgmentLineY;
        return (long) Math.max(0, diffY / pixelsPerTick);
    }

    private void scrollToTick(long tick) {
        int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - (int) (tick / TICKS_PER_ROW);
        if (targetRow < 0) return;
        Rectangle rect = table.getCellRect(targetRow, 0, true);
        int targetY = rect.y - scrollPane.getViewport().getHeight() + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
        scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetY)));
    }

    private void saveTableToTxt() {
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            int count = 0;
            for (int r = tableModel.getRowCount() - 1; r >= 0; r--) {
                for (int c = 0; c < COLUMN_COUNT; c++) {
                    if (tableModel.getValueAt(r, c) instanceof NoteData nd) {
                        int timeT = (tableModel.getRowCount() - 1 - JUDGMENT_LINE_OFFSET - r) * 10;
                        out.println("{\"pitch\":" + nd.pitch + ", \"t\":" + timeT + "},");
                        count++;
                    }
                }
            }
            lblStatus.setText(" [성공] " + count + "개의 노트를 저장했습니다. (Ctrl+S)");
        } catch (Exception e) {
            lblStatus.setText(" [오류] 저장 실패!");
            e.printStackTrace();
        }
    }

    private void deleteSelected() {
        int r = table.getSelectedRow(), c = table.getSelectedColumn();
        if (r != -1 && table.getValueAt(r, c) != null) {
            saveState();
            tableModel.setValueAt(null, r, c);
            lblStatus.setText(" 노트 삭제 완료");
        }
    }

    private void saveState() {
        undoStack.push(getCurrentState());
        redoStack.clear();
    }

    private void undo() {
        if (!undoStack.isEmpty()) {
            redoStack.push(getCurrentState());
            restoreState(undoStack.pop());
            lblStatus.setText(" 실행 취소(Undo)");
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            undoStack.push(getCurrentState());
            restoreState(redoStack.pop());
            lblStatus.setText(" 다시 실행(Redo)");
        }
    }

    private Object[][] getCurrentState() {
        Object[][] state = new Object[tableModel.getRowCount()][COLUMN_COUNT];
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                state[r][c] = tableModel.getValueAt(r, c);
            }
        }
        return state;
    }

    private void restoreState(Object[][] state) {
        for (int r = 0; r < state.length; r++) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                tableModel.setValueAt(state[r][c], r, c);
            }
        }
    }

    private void setupColumns() {
        String[] headers = {"SCR", "S", "D", "F", "SPACE", "J", "K", "L"};
        for (int i = 0; i < COLUMN_COUNT; i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            col.setHeaderValue(headers[i]);
            col.setPreferredWidth(i == 4 ? 120 : 90);
            col.setCellRenderer(new NoteCellRenderer());
        }
    }

    private class NoteCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof NoteData data) {
                c.setBackground(getOctaveColor(column, (data.pitch / 12) - 1));
                c.setForeground(Color.BLACK);
                setText(data.pitch + "(" + NOTE_NAMES[data.pitch % 12] + (data.pitch/12-1) + ")");
                setHorizontalAlignment(SwingConstants.CENTER);
            } else {
                c.setBackground(row == table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET ? new Color(70, 20, 20) : Color.BLACK);
                setText("");
            }
            if (isSelected) c.setBackground(c.getBackground().brighter());
            return c;
        }
    }

    private Color getOctaveColor(int col, int octave) {
        if (col == 0) return new Color(255, 180, 180);
        if (col == 4) return new Color(255, 255, 180);
        return new Color(180, 220, 255);
    }

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) { tableModel.setRowCount(3000); return; }
            Sequence seq = MidiSystem.getSequence(file);
            sequencer.setSequence(seq);
            int totalRows = (int) (seq.getTickLength() / TICKS_PER_ROW) + 500;
            tableModel.setRowCount(totalRows);
            for (Track track : seq.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage msg = event.getMessage();
                    if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        int row = (totalRows - 1) - JUDGMENT_LINE_OFFSET - (int)(event.getTick() / TICKS_PER_ROW);
                        int col = (sm.getData1() % 12) % COLUMN_COUNT;
                        if (row >= 0) tableModel.setValueAt(new NoteData(sm.getData1()), row, col);
                    }
                }
            }
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

    private class NoteTransferHandler extends TransferHandler {
        @Override public int getSourceActions(JComponent c) { return MOVE; }
        
        @Override protected Transferable createTransferable(JComponent c) {
            int r = table.getSelectedRow(), col = table.getSelectedColumn();
            NoteData nd = (NoteData) table.getValueAt(r, col);
            // 드래그 시 원본의 행(r)과 열(col) 정보를 함께 저장하여 전달합니다.
            return nd != null ? new NoteTransferable(nd, r, col) : null;
        }

        @Override public boolean canImport(TransferSupport support) { 
            return support.isDataFlavorSupported(NoteTransferable.FLAVOR); 
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            JTable.DropLocation dl = (JTable.DropLocation) support.getDropLocation();
            
            try {
                NoteTransferable t = (NoteTransferable) support.getTransferable().getTransferData(NoteTransferable.FLAVOR);
                
                // [핵심] 드롭된 위치의 행이 원본 행과 다르면 이동을 취소합니다. (열 이동만 허용)
                if (dl.getRow() != t.sourceRow) {
                    lblStatus.setText(" [알림] 열(레인) 이동만 가능합니다.");
                    return false; 
                }

                saveState(); // 상태 저장 (Undo용)

                // 위치 교환 로직
                // 1. 타겟 위치(드롭된 곳)에 있던 데이터를 가져옴
                NoteData targetData = (NoteData) table.getValueAt(dl.getRow(), dl.getColumn());
                
                // 2. 원래 시작 위치에 타겟 데이터를 배치
                tableModel.setValueAt(targetData, t.sourceRow, t.sourceCol);
                
                // 3. 타겟 위치에 드래그해온 데이터를 배치
                tableModel.setValueAt(t.data, dl.getRow(), dl.getColumn());
                
                lblStatus.setText(" 노트 위치 교환 완료 (Column: " + t.sourceCol + " -> " + dl.getColumn() + ")");
                return true;
            } catch (Exception e) { 
                return false; 
            }
        }
    }

    private static class NoteTransferable implements Transferable {
        public static final DataFlavor FLAVOR = new DataFlavor(NoteData.class, "NoteData");
        NoteData data; int sourceRow, sourceCol;
        NoteTransferable(NoteData d, int r, int c) { data = d; sourceRow = r; sourceCol = c; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{FLAVOR}; }
        @Override public boolean isDataFlavorSupported(DataFlavor f) { return FLAVOR.equals(f); }
        @Override public Object getTransferData(DataFlavor f) { return this; }
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