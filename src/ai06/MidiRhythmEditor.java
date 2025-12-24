package ai06;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.File;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;

    private final int TICKS_PER_ROW = 20; 
    private final int COLUMN_COUNT = 8; // 물리적인 레인 개수 (SCR, S, D, F, SPACE, J, K, L)

    // 12음계 매핑 참고용 (내부 로직에서 사용)
    private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

    private double currentBPM = 120.0;
    private int resolution = 480;

    public MidiRhythmEditor() {
        setTitle("Rhythm Game MIDI Editor (8-Lane Fixed + 12-Note Mapping)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 1000);

        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 마지막 행으로 스크롤 이동
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
        table.setRowHeight(25);
        table.setBackground(Color.BLACK);
        table.setGridColor(Color.DARK_GRAY);
        table.setSelectionBackground(new Color(100, 100, 100, 150));
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
            cm.getColumn(i).setPreferredWidth(i == 4 ? 80 : 55);
            if (i < headers.length) cm.getColumn(i).setHeaderValue(headers[i]);

            final int colIndex = i;
            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                        boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    
                    if (value instanceof NoteData) {
                        NoteData data = (NoteData) value;
                        c.setBackground(getNoteColor(column, data.pitch));
                        // 셀에 음계 이름 표시 (옵션)
                        setText(NOTE_NAMES[data.pitch % 12]);
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setForeground(Color.BLACK);
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

    // 컬럼 고유 색상 + 음계에 따른 미세한 변화 가능
    private Color getNoteColor(int col, int pitch) {
        boolean isBlackKey = isMidiBlackKey(pitch);
        switch (col) {
            case 0: return Color.PINK; // SCR
            case 4: return Color.YELLOW; // SPACE
            default: 
                // 검은 건반 음계일 경우 조금 더 진한 색상으로 표현하여 구분
                return isBlackKey ? new Color(0, 150, 200) : Color.CYAN;
        }
    }

    private boolean isMidiBlackKey(int pitch) {
        int note = pitch % 12;
        return note == 1 || note == 3 || note == 6 || note == 8 || note == 10;
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

    public void loadMidiFile(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return;

            Sequence sequence = MidiSystem.getSequence(file);
            this.resolution = sequence.getResolution();

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
                            if (actualRow < 0) continue;

                            // 12음계를 8개 레인에 매핑 (나머지 연산 후 레인 범위 조절)
                            int targetCol = (pitch % 12) % COLUMN_COUNT;
                            
                            // 겹침 방지 로직 실행
                            int finalCol = findEmptyColumn(actualRow, targetCol);
                            
                            if (finalCol != -1) {
                                tableModel.setValueAt(new NoteData(pitch, tick), actualRow, finalCol);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int findEmptyColumn(int row, int targetCol) {
        if (tableModel.getValueAt(row, targetCol) == null) return targetCol;

        for (int i = 1; i < COLUMN_COUNT; i++) {
            int right = targetCol + i;
            if (right < COLUMN_COUNT && tableModel.getValueAt(row, right) == null) return right;
            int left = targetCol - i;
            if (left >= 0 && tableModel.getValueAt(row, left) == null) return left;
        }
        return -1;
    }

    // 노트 정보를 담을 간단한 클래스
    static class NoteData {
        int pitch;
        long tick;
        NoteData(int pitch, long tick) {
            this.pitch = pitch;
            this.tick = tick;
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}