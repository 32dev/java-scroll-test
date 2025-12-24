package ai04;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.File;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;

    private final int TICKS_PER_ROW = 20; 
    private final int COLUMN_COUNT = 8;

    private double currentBPM = 120.0;
    private int resolution = 480;

    public MidiRhythmEditor() {
        setTitle("Rhythm Game MIDI Editor (Overlap Prevention)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 1000);

        initializeComponents();

        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 마지막 행으로 스크롤 이동
        SwingUtilities.invokeLater(() -> {
            int lastRow = table.getRowCount() - 1;
            table.changeSelection(lastRow, 0, false, false);
        });
    }

    private void initializeComponents() {
        tableModel = new DefaultTableModel(500, COLUMN_COUNT) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
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
            if (i < headers.length)
                cm.getColumn(i).setHeaderValue(headers[i]);

            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                        boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value != null) {
                        c.setBackground(getNoteColor(column));
                    } else {
                        c.setBackground(Color.BLACK);
                    }
                    ((JComponent) c).setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.DARK_GRAY));
                    return c;
                }
            });
        }
    }

    private Color getNoteColor(int col) {
        switch (col) {
            case 0: return Color.PINK;
            case 4: return Color.YELLOW;
            default: return Color.CYAN;
        }
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
                            
                            // 1. 실제 행 인덱스 계산 (아래가 0인 리듬게임 방식)
                            int actualRow = tableModel.getRowCount() - 1 - row;
                            if (actualRow < 0) continue;

                            // 2. 초기 컬럼 설정
                            int startCol = pitch % COLUMN_COUNT;
                            
                            // 3. 겹침 방지 로직: 해당 셀이 이미 차있으면 빈 옆 칸 찾기
                            int finalCol = findEmptyColumn(actualRow, startCol);
                            
                            if (finalCol != -1) {
                                tableModel.setValueAt(tick, actualRow, finalCol);
                                System.out.println("Row: " + actualRow + " | Pitch: " + pitch + " -> Col: " + finalCol);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 해당 행에서 가장 가까운 빈 컬럼을 찾는 메서드
     */
    private int findEmptyColumn(int row, int targetCol) {
        // 원래 자리가 비어있으면 즉시 반환
        if (tableModel.getValueAt(row, targetCol) == null) {
            return targetCol;
        }

        // 비어있지 않다면 왼쪽/오른쪽으로 한 칸씩 넓혀가며 빈 공간 탐색
        for (int i = 1; i < COLUMN_COUNT; i++) {
            // 오른쪽 탐색
            int right = targetCol + i;
            if (right < COLUMN_COUNT && tableModel.getValueAt(row, right) == null) {
                return right;
            }
            // 왼쪽 탐색
            int left = targetCol - i;
            if (left >= 0 && tableModel.getValueAt(row, left) == null) {
                return left;
            }
        }
        
        // 모든 컬럼이 꽉 찼다면 (거의 없겠지만) -1 반환
        return -1;
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}