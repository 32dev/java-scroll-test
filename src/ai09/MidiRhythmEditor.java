package ai09;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
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

    public MidiRhythmEditor() {
        setTitle("Rhythm Editor - Drag & Drop Swap (Horizontal Only)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

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

    private void initializeComponents() {
        tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(50, 50, 50));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // 드래그 혼선 방지
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        setupColumns();
        setupDragAndDrop(); // 드래그 앤 드롭 리스너 추가

        scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
        setupRowHeader();
    }

    // --- 드래그 앤 드롭 (좌우 이동 및 스왑) 로직 ---
    private void setupDragAndDrop() {
        MouseAdapter ma = new MouseAdapter() {
            private int startCol = -1;
            private int startRow = -1;
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
                // 수직 고정: startRow를 그대로 사용하여 같은 행 내에서만 이동
                if (endCol != -1 && endCol != startCol) {
                    Object targetVal = table.getValueAt(startRow, endCol);
                    
                    // 위치 교환(Swap)
                    table.setValueAt(targetVal, startRow, startCol); // 대상 위치의 것을 원래 자리로
                    table.setValueAt(dragData, startRow, endCol);   // 드래그한 것을 대상 자리로
                    
                    saveTableToTxt(); // 변경될 때마다 파일 갱신
                }
                
                dragData = null;
                table.setCursor(Cursor.getDefaultCursor());
                table.repaint();
            }
        };
        table.addMouseListener(ma);
        table.addMouseMotionListener(ma);
    }

    private void setupColumns() {
        String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
        TableColumnModel cm = table.getColumnModel();

        for (int i = 0; i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(i == 4 ? 90 : 65);
            cm.getColumn(i).setHeaderValue(headers[i]);
            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                        boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value instanceof NoteData) {
                        NoteData data = (NoteData) value;
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
        switch (col) {
            case 0: base = Color.PINK; break;
            case 4: base = Color.YELLOW; break;
            default: base = Color.CYAN; break;
        }
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float brightness = 0.4f + (Math.max(0, octave) * 0.08f);
        return Color.getHSBColor(hsb[0], hsb[1], Math.min(1.0f, brightness));
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
                            int pitch = sm.getData1();
                            long tick = event.getTick();
                            int row = (int) (tick / TICKS_PER_ROW);
                            int actualRow = tableModel.getRowCount() - 1 - row;
                            if (actualRow >= 0) {
                                int targetCol = (pitch % 12) % COLUMN_COUNT;
                                int finalCol = findEmptyColumn(actualRow, targetCol);
                                if (finalCol != -1) {
                                    tableModel.setValueAt(new NoteData(pitch, tick, row), actualRow, finalCol);
                                }
                            }
                        }
                    }
                }
            }
            saveTableToTxt();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // 테이블의 현재 상태를 기반으로 TXT 파일 저장
    private void saveTableToTxt() {
        StringBuilder sb = new StringBuilder();
        // 테이블 전체를 순회하며 노트를 찾아 시간순(아래행부터)으로 기록
        for (int r = tableModel.getRowCount() - 1; r >= 0; r--) {
            for (int c = 0; c < COLUMN_COUNT; c++) {
                Object val = tableModel.getValueAt(r, c);
                if (val instanceof NoteData) {
                    NoteData nd = (NoteData) val;
                    sb.append("{\"seq\":").append(nd.pitch)
                      .append(", \"t\":").append(nd.originalRow * 10).append(" },\n");
                }
            }
        }
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(sb.toString());
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

    static class NoteData {
        int pitch;
        long tick;
        int originalRow; // 시간(t) 계산용 행 정보 유지
        NoteData(int pitch, long tick, int row) {
            this.pitch = pitch;
            this.tick = tick;
            this.originalRow = row;
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}