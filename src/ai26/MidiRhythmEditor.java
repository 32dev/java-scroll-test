package ai26;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Stack;

import javax.sound.midi.*;
import javax.swing.*;
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
    private Receiver midiReceiver; 
    private Timer uiSyncTimer;
    private float speedMultiplier = 1.0f; 
    private int lastPlayedRow = -1; 

    private Stack<Object[][]> undoStack = new Stack<>();
    private Stack<Object[][]> redoStack = new Stack<>();

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor Pro - [F5] Play | Click Note to Hear");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 800);

        initMidiEngine();
        initializeComponents();
        initializeMenu();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        this.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> scrollToTick(0));
    }

    private void initMidiEngine() {
        try {
            sequencer = MidiSystem.getSequencer();
            sequencer.open();
            midiReceiver = sequencer.getReceiver(); 
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
        
        // --- [추가] 마우스 클릭 시 소리 재생 로직 ---
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row != -1 && col != -1) {
                    Object val = table.getValueAt(row, col);
                    if (val instanceof NoteData nd) {
                        playNote(nd.pitch); // 전체 재생이 아닌 해당 노트만 소리 출력
                        lblStatus.setText(" [Preview] Pitch: " + nd.pitch + " (" + NOTE_NAMES[nd.pitch%12] + (nd.pitch/12-1) + ")");
                    }
                }
            }
        });

        table.setDragEnabled(true);
        table.setDropMode(DropMode.USE_SELECTION);
        table.setTransferHandler(new NoteTransferHandler());

        setupColumns();

        scrollPane = new JScrollPane(table);
        scrollPane.getVerticalScrollBar().setUnitIncrement(30);
        
        lblStatus = new JLabel(" [F5]: 전체 재생 | [노트 클릭]: 단발 소리 확인");
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

        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "togglePlay");
        am.put("togglePlay", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { togglePlayback(); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        am.put("save", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { saveTableToTxt(); } });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "speedDown");
        am.put("speedDown", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { adjustSpeed(-0.2f); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "speedUp");
        am.put("speedUp", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { adjustSpeed(0.2f); } });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        am.put("undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { undo(); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        am.put("redo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { redo(); } });
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        am.put("delete", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { deleteSelected(); } });
    }

    private void togglePlayback() {
        if (sequencer == null) return;
        if (sequencer.isRunning()) {
            sequencer.stop();
            if (uiSyncTimer != null) uiSyncTimer.stop();
            lastPlayedRow = -1;
            lblStatus.setText(" ■ 정지됨");
        } else {
            long currentTick = calculateTickFromView();
            sequencer.setTickPosition(currentTick);
            sequencer.start();
            if (uiSyncTimer != null) uiSyncTimer.stop();
            uiSyncTimer = new Timer(10, e -> syncTableSmooth());
            uiSyncTimer.start();
            lblStatus.setText(" ▶ 재생 중...");
        }
    }

    private void playNote(int pitch) {
        try {
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, 0, pitch, 100);
            midiReceiver.send(on, -1);
            
            // 300ms 후 소리 끄기 (클릭 시 들리는 소리 지속 시간)
            Timer offTimer = new Timer(300, e -> {
                try {
                    ShortMessage off = new ShortMessage();
                    off.setMessage(ShortMessage.NOTE_OFF, 0, pitch, 0);
                    midiReceiver.send(off, -1);
                } catch (Exception ex) {}
            });
            offTimer.setRepeats(false);
            offTimer.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void syncTableSmooth() {
        if (!sequencer.isRunning()) return;
        long currentTick = sequencer.getTickPosition();
        int rowHeight = table.getRowHeight();
        float pixelsPerTick = (float)rowHeight / TICKS_PER_ROW;
        
        float currentNoteY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * rowHeight) - (currentTick * pixelsPerTick);
        int targetViewY = (int)(currentNoteY - (scrollPane.getViewport().getHeight() - (JUDGMENT_LINE_OFFSET * rowHeight)));
        scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetViewY)));

        int currentRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - (int)(currentTick / TICKS_PER_ROW);
        if (currentRow != lastPlayedRow && currentRow >= 0 && currentRow < table.getRowCount()) {
            for (int col = 0; col < COLUMN_COUNT; col++) {
                Object val = tableModel.getValueAt(currentRow, col);
                if (val instanceof NoteData nd) {
                    playNote(nd.pitch);
                }
            }
            lastPlayedRow = currentRow;
        }
    }

    private void adjustSpeed(float delta) {
        long currentTick = calculateTickFromView();
        speedMultiplier = Math.max(0.2f, Math.min(5.0f, speedMultiplier + delta));
        int newRowHeight = (int)(24 * speedMultiplier);
        table.setRowHeight(newRowHeight);
        ((JList<?>)scrollPane.getRowHeader().getView()).setFixedCellHeight(newRowHeight);
        scrollToTick(currentTick);
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
            lblStatus.setText(" [성공] 저장 완료!");
        } catch (Exception e) { lblStatus.setText(" [오류] 저장 실패!"); }
    }

    private void deleteSelected() {
        int r = table.getSelectedRow(), c = table.getSelectedColumn();
        if (r != -1 && table.getValueAt(r, c) != null) {
            saveState();
            tableModel.setValueAt(null, r, c);
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
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            undoStack.push(getCurrentState());
            restoreState(redoStack.pop());
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
            return nd != null ? new NoteTransferable(nd, r, col) : null;
        }
        @Override public boolean canImport(TransferSupport support) { return support.isDataFlavorSupported(NoteTransferable.FLAVOR); }
        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            JTable.DropLocation dl = (JTable.DropLocation) support.getDropLocation();
            try {
                NoteTransferable t = (NoteTransferable) support.getTransferable().getTransferData(NoteTransferable.FLAVOR);
                if (dl.getRow() != t.sourceRow) return false; 
                saveState();
                NoteData targetData = (NoteData) table.getValueAt(dl.getRow(), dl.getColumn());
                tableModel.setValueAt(targetData, t.sourceRow, t.sourceCol);
                tableModel.setValueAt(t.data, dl.getRow(), dl.getColumn());
                return true;
            } catch (Exception e) { return false; }
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