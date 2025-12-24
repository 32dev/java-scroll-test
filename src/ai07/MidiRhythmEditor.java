package ai07;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.io.File;

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
import javax.swing.SwingConstants;
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

    private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

    private double currentBPM = 120.0;
    private int resolution = 480;

    public MidiRhythmEditor() {
        setTitle("Rhythm Game Editor (Lane Sync + Octave Display)");
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
        table.setRowHeight(28); // 텍스트 가독성을 위해 높이 상향
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(50, 50, 50));
        table.setSelectionBackground(new Color(255, 255, 255, 40));
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
            cm.getColumn(i).setPreferredWidth(i == 4 ? 90 : 65);
            if (i < headers.length) cm.getColumn(i).setHeaderValue(headers[i]);

            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                        boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    
                    if (value instanceof NoteData) {
                        NoteData data = (NoteData) value;
                        int octave = (data.pitch / 12) - 1; // MIDI 표준 옥타브 계산
                        String noteName = NOTE_NAMES[data.pitch % 12];
                        
                        // 배경색: 옥타브가 높을수록 더 밝은 색상 적용
                        c.setBackground(getOctaveColor(column, octave));
                        
                        // 텍스트: 음계 + 옥타브 (예: C4)
                        setText(noteName + octave);
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setFont(new Font("SansSerif", Font.BOLD, 11));
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

    // 옥타브 정보에 따른 색상 가공
    private Color getOctaveColor(int col, int octave) {
        Color base;
        switch (col) {
            case 0: base = Color.PINK; break;
            case 4: base = Color.YELLOW; break;
            default: base = Color.CYAN; break;
        }
        
        // 옥타브에 따라 명도 조절 (옥타브가 높을수록 밝아짐)
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float brightness = 0.3f + (octave * 0.1f); // 옥타브 0~9 범위를 0.3~1.0 밝기로 매핑
        if (brightness > 1.0f) brightness = 1.0f;
        
        return Color.getHSBColor(hsb[0], hsb[1], brightness);
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

                            // 레인 매핑: 음계 기반
                            int targetCol = (pitch % 12) % COLUMN_COUNT;
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