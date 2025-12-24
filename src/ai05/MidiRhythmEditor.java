package ai05;

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
    // 12음계 설정을 위해 컬럼 수를 12개로 변경
    private final int COLUMN_COUNT = 12;

    // 12음계 이름 정의
    private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

    private double currentBPM = 120.0;
    private int resolution = 480;

    public MidiRhythmEditor() {
        setTitle("Rhythm Game MIDI Editor (12-Note Scale)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 800); // 12개 컬럼을 위해 가로를 조금 넓힘

        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        // 맨 아래로 초기 스크롤
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
        table.setGridColor(new Color(60, 60, 60));
        table.setSelectionBackground(new Color(255, 255, 255, 30));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        setupColumns();
        scrollPane = new JScrollPane(table);
        scrollPane.getViewport().setBackground(Color.BLACK);
        add(scrollPane, BorderLayout.CENTER);
        setupRowHeader();
    }

    private void setupColumns() {
        TableColumnModel cm = table.getColumnModel();

        for (int i = 0; i < COLUMN_COUNT; i++) {
            cm.getColumn(i).setPreferredWidth(60);
            cm.getColumn(i).setHeaderValue(NOTE_NAMES[i]);

            final int noteIndex = i;
            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                        boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    
                    // 1. 기본 배경색 설정 (검은 건반/흰 건반 구분)
                    if (isBlackKey(noteIndex)) {
                        c.setBackground(new Color(30, 30, 30)); // 검은 건반 영역 어둡게
                    } else {
                        c.setBackground(Color.BLACK);
                    }

                    // 2. 노트가 있을 경우 색상 표시
                    if (value instanceof Integer) {
                        c.setBackground(getNoteColor(noteIndex));
                        c.setForeground(Color.WHITE);
                    }

                    ((JComponent) c).setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.DARK_GRAY));
                    return c;
                }
            });
        }
    }

    // 샵(#)이 붙은 음계인지 판단
    private boolean isBlackKey(int index) {
        return index == 1 || index == 3 || index == 6 || index == 8 || index == 10;
    }

    private Color getNoteColor(int col) {
        if (isBlackKey(col)) return new Color(255, 100, 0); // 검은 건반 노트는 주황색 계열
        return new Color(0, 200, 255); // 흰 건반 노트는 하늘색 계열
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
            if (!file.exists()) {
                tableModel.setRowCount(500);
                return;
            }

            Sequence sequence = MidiSystem.getSequence(file);
            this.resolution = sequence.getResolution();
            int totalRows = (int) (sequence.getTickLength() / TICKS_PER_ROW) + 100;
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
                            
                            // 12음계 매핑 (0:C, 1:C#, ... 11:B)
                            int col = pitch % 12;
                            
                            int row = (int) (tick / TICKS_PER_ROW);
                            int actualRow = tableModel.getRowCount() - 1 - row;

                            if (actualRow >= 0) {
                                // 셀에 MIDI Tick 값을 저장 (나중에 활용 가능)
                                tableModel.setValueAt(pitch, actualRow, col);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}