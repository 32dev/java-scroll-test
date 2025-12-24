package ai10;

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

    // 실행 취소 / 다시 실행을 위한 스택
    private Stack<UndoItem> undoStack = new Stack<>();
    private Stack<UndoItem> redoStack = new Stack<>();

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - Undo/Redo & Manual Save");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

        initializeMenu(); // 메뉴바 추가
        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        SwingUtilities.invokeLater(() -> {
            if (table.getRowCount() > 0) {
                int lastRow = table.getRowCount() - 1;
                table.changeSelection(lastRow, 0, false, false);
            }
        });
    }

    private void initializeMenu() {
        JMenuBar menuBar = new JMenuBar();
        
        JButton btnSave = new JButton("저장 (Ctrl+S)");
        JButton btnUndo = new JButton("실행 취소 (Ctrl+Z)");
        JButton btnRedo = new JButton("다시 실행 (Ctrl+Y)");

        btnSave.addActionListener(e -> saveTableToTxt());
        btnUndo.addActionListener(e -> undo());
        btnRedo.addActionListener(e -> redo());

        menuBar.add(btnSave);
        menuBar.add(btnUndo);
        menuBar.add(btnRedo);
        setJMenuBar(menuBar);

        // 단축키 설정
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        getRootPane().getActionMap().put("undo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { undo(); } });

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        getRootPane().getActionMap().put("redo", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { redo(); } });

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        getRootPane().getActionMap().put("save", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { saveTableToTxt(); } });
    }

    private void initializeComponents() {
        tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(50, 50, 50));
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

            @Override
            public void mousePressed(MouseEvent e) {
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

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragData == null) return;
                int endCol = table.columnAtPoint(e.getPoint());
                if (endCol != -1 && endCol != startCol) {
                    Object targetVal = table.getValueAt(startRow, endCol);
                    
                    // Undo를 위한 상태 기록
                    pushUndo(startRow, startCol, endCol, dragData, (NoteData)targetVal);
                    
                    table.setValueAt(targetVal, startRow, startCol);
                    table.setValueAt(dragData, startRow, endCol);
                }
                dragData = null;
                table.setCursor(Cursor.getDefaultCursor());
                table.repaint();
            }
        };
        table.addMouseListener(ma);
        table.addMouseMotionListener(ma);
    }

    private void pushUndo(int row, int fromCol, int toCol, NoteData fromData, NoteData toData) {
        undoStack.push(new UndoItem(row, fromCol, toCol, fromData, toData));
        redoStack.clear(); // 새로운 작업 발생 시 Redo 스택 초기화
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        UndoItem item = undoStack.pop();
        redoStack.push(item);
        table.setValueAt(item.fromData, item.row, item.fromCol);
        table.setValueAt(item.toData, item.row, item.toCol);
        table.repaint();
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        UndoItem item = redoStack.pop();
        undoStack.push(item);
        table.setValueAt(item.toData, item.row, item.fromCol);
        table.setValueAt(item.fromData, item.row, item.toCol);
        table.repaint();
    }

    private void saveTableToTxt() {
        StringBuilder sb = new StringBuilder();
        for (int r = tableModel.getRowCount() - 1; r >= 0; r--) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                Object val = tableModel.getValueAt(r, c);
                if (val instanceof NoteData) {
                    NoteData nd = (NoteData) val;
                    sb.append("{\"seq\":").append(nd.pitch).append(", \"t\":").append(nd.originalRow * 10).append(" },\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(sb.toString());
            JOptionPane.showMessageDialog(this, "성공적으로 저장되었습니다! (output.txt)");
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- 이하 기존 로직 (동일) ---
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
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float b = 0.4f + (Math.max(0, octave) * 0.08f);
        return Color.getHSBColor(hsb[0], hsb[1], Math.min(1.0f, b));
    }

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return;
            Sequence sequence = MidiSystem.getSequence(file);
            int totalRows = (int) (sequence.getTickLength() / TICKS_PER_ROW) + 150;
            tableModel.setRowCount(totalRows);
            for (Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage message = event.getMessage();
                    if (message instanceof ShortMessage) {
                        ShortMessage sm = (ShortMessage) message;
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                            int pitch = sm.getData1(), row = (int) (event.getTick() / TICKS_PER_ROW);
                            int actualRow = tableModel.getRowCount() - 1 - row;
                            if (actualRow >= 0) {
                                int tc = (pitch % 12) % COLUMN_COUNT, fc = findEmptyColumn(actualRow, tc);
                                if (fc != -1) tableModel.setValueAt(new NoteData(pitch, event.getTick(), row), actualRow, fc);
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
            public String getElementAt(int index) { return String.valueOf(table.getRowCount() - index); }
        });
        rowHeader.setFixedCellHeight(table.getRowHeight());
        rowHeader.setFixedCellWidth(50);
        rowHeader.setBackground(new Color(30, 30, 30));
        rowHeader.setForeground(Color.LIGHT_GRAY);
        scrollPane.setRowHeaderView(rowHeader);
    }

    // 데이터 보관용 클래스들
    static class NoteData {
        int pitch; long tick; int originalRow;
        NoteData(int pitch, long tick, int row) { this.pitch = pitch; this.tick = tick; this.originalRow = row; }
    }

    static class UndoItem {
        int row, fromCol, toCol; NoteData fromData, toData;
        UndoItem(int r, int fc, int tc, NoteData fd, NoteData td) {
            this.row = r; this.fromCol = fc; this.toCol = tc; this.fromData = fd; this.toData = td;
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}